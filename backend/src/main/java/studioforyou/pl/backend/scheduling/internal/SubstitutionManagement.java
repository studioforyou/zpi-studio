package studioforyou.pl.backend.scheduling.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.scheduling.SubstitutionOffers;
import studioforyou.pl.backend.scheduling.SubstitutionOpened;
import studioforyou.pl.backend.scheduling.ScheduleEventChanged;
import studioforyou.pl.backend.scheduling.ScheduleEventCancelled;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignmentScope;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ChangeAssignmentsRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ResourceAssignmentRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.StaffAssignmentRequest;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.Action;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.Assignment;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.Candidate;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.Offer;
import studioforyou.pl.backend.scheduling.internal.SubstitutionEligibility.Key;
import studioforyou.pl.backend.scheduling.internal.SubstitutionEligibility.Result;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Event, request, staff schedule lock, then staff row: the same order for every transition. */
@Service
@Transactional
class SubstitutionManagement implements SubstitutionOffers {

	private static final String SELECT = """
			SELECT r.*, e.version AS event_version, e.kind, e.status AS event_status, e.service_type_id,
			       e.starts_at, e.ends_at,
			       CASE WHEN e.kind = 'PERSONAL_TRAINING' THEN COALESCE(t.name, 'Trening personalny')
			            ELSE COALESCE(e.title, t.name, 'Zajęcia') END AS title,
			       concat_ws(' ', o.first_name, o.last_name) AS original_name,
			       NULLIF(concat_ws(' ', s.first_name, s.last_name), '') AS substitute_name,
			       COALESCE((SELECT string_agg(room.name, ', ' ORDER BY room.name) FROM event_resource er
			         JOIN resource room ON room.id = er.resource_id WHERE er.event_id = e.id), 'Studio') AS rooms,
			       (EXISTS (SELECT 1 FROM event_staff es WHERE es.event_id = e.id
			         AND es.staff_id = r.original_staff_id AND es.role = 'LEAD')
			         AND e.kind IN ('CLASS','PERSONAL_TRAINING')
			         AND (r.time_off_id IS NULL OR EXISTS (SELECT 1 FROM staff_time_off t WHERE t.id=r.time_off_id
			           AND t.status='APPROVED' AND e.starts_at<t.ends_at AND e.ends_at>t.starts_at))) AS current_lead
			FROM substitution_request r JOIN schedule_event e ON e.id = r.event_id
			JOIN staff_profile o ON o.id = r.original_staff_id
			LEFT JOIN staff_profile s ON s.id = r.substitute_staff_id
			LEFT JOIN service_type t ON t.id = e.service_type_id
			""";

	private final JdbcClient jdbc;
	private final ScheduleEventRepository events;
	private final SchedulePlanner planner;
	private final ScheduleLocks locks;
	private final StaffProfiles staff;
	private final SubstitutionEligibility eligibility;
	private final StudioSettings studio;
	private final AuditLog audit;
	private final ApplicationEventPublisher publisher;

	SubstitutionManagement(JdbcClient jdbc, ScheduleEventRepository events, SchedulePlanner planner,
			ScheduleLocks locks, StaffProfiles staff, SubstitutionEligibility eligibility, StudioSettings studio,
			AuditLog audit, ApplicationEventPublisher publisher) {
		this.jdbc = jdbc; this.events = events; this.planner = planner; this.locks = locks; this.staff = staff;
		this.eligibility = eligibility; this.studio = studio; this.audit = audit; this.publisher = publisher;
	}

	record Row(UUID id, UUID eventId, int version, int eventVersion, String status, String eventStatus,
			String kind, String title, String rooms, @Nullable UUID serviceTypeId, Instant startsAt, Instant endsAt,
			UUID originalStaffId, String originalName, @Nullable UUID substituteStaffId,
			@Nullable String substituteName, boolean currentLead, @Nullable String decisionNote) {
		boolean live() { return this.eventStatus.equals("SCHEDULED") && this.startsAt.isAfter(Instant.now()); }
		boolean actionable() { return live() && this.currentLead && (this.status.equals("OPEN") || this.status.equals("CLAIMED")); }
	}

	@Transactional(readOnly = true)
	List<Offer> offers(UUID viewer, Instant from, Instant to, boolean mine) {
		var rows = rows(from, to).stream().filter((r) -> mine ? viewer.equals(r.substituteStaffId())
				: r.status().equals("OPEN") && r.actionable() && !viewer.equals(r.originalStaffId())).toList();
		var checks = this.eligibility.assess(rows, this.staff.findAllById(List.of(viewer)));
		boolean approval = this.studio.substitutionNeedsApproval();
		return rows.stream().map((r) -> response(r, viewer, checks.get(new Key(r.id(), viewer)), false, approval)).toList();
	}

	@Transactional(readOnly = true)
	Offer offer(UUID id, UUID viewer) {
		Row row = row(id);
		// A stale notification can still explain that the offer has closed, without exposing a reason or participant.
		var check = this.eligibility.assess(List.of(row), this.staff.findAllById(List.of(viewer))).get(new Key(id, viewer));
		return response(row, viewer, check, false);
	}

	@Transactional(readOnly = true)
	List<Offer> admin(Instant from, Instant to) {
		boolean approval = this.studio.substitutionNeedsApproval();
		return rows(from, to).stream().map((r) -> response(r, null, null, true, approval)).toList();
	}

	@Transactional(readOnly = true)
	List<Candidate> candidates(UUID id) {
		Row row = row(id);
		var candidates = this.staff.findAll(true).stream().filter((s) -> !s.id().equals(row.originalStaffId())).toList();
		var checks = this.eligibility.assess(List.of(row), candidates);
		return candidates.stream().map((s) -> {
			Result check = checks.get(new Key(id, s.id()));
			return new Candidate(s.id(), s.displayName(), row.actionable() && check.available(),
					row.actionable() ? check.reason() : "CLOSED", check.warnings());
		}).toList();
	}

	Offer claim(UUID id, UUID viewer, UUID actor, Action input) {
		ScheduleEvent event = lockEvent(id);
		Row row = lock(id, input.version());
		requireStatus(row, "OPEN");
		requireEligible(row, viewer);
		boolean approval = this.studio.substitutionNeedsApproval();
		if (!approval) transfer(event, row.originalStaffId(), viewer);
		this.jdbc.sql("""
				UPDATE substitution_request SET status = :status, substitute_staff_id = :staff,
				claimed_at = now(), decided_by = :actor, decided_at = :decidedAt,
				decision_note = NULL, version = version + 1 WHERE id = :id
				""").param("id", id).param("status", approval ? "CLAIMED" : "APPROVED").param("staff", viewer)
			.param("actor", approval ? null : actor).param("decidedAt", approval ? null : Timestamp.from(Instant.now())).update();
		record(row, actor, approval ? "CLAIMED" : "APPROVED", viewer, null);
		return offer(id, viewer);
	}

	Offer withdraw(UUID id, UUID viewer, UUID actor, Action input) {
		lockEvent(id);
		Row row = lock(id, input.version());
		if (!viewer.equals(row.substituteStaffId())) throw new ApiException(ErrorCode.NOT_FOUND, "Claim not found.");
		requireStatus(row, "CLAIMED");
		closeAndReopen(row, actor, "CANCELLED", null);
		return response(row(id), viewer, null, false);
	}

	Offer approve(UUID id, UUID actor, Action input) {
		ScheduleEvent event = lockEvent(id);
		Row row = lock(id, input.version());
		requireStatus(row, "CLAIMED");
		UUID candidate = row.substituteStaffId();
		if (candidate == null) throw new ApiException(ErrorCode.SUBSTITUTION_ALREADY_DECIDED, "No candidate.");
		requireEligible(row, candidate);
		transfer(event, row.originalStaffId(), candidate);
		decide(row, actor, "APPROVED", candidate, input.note());
		return response(row(id), null, null, true);
	}

	Offer reject(UUID id, UUID actor, Action input) {
		lockEvent(id);
		Row row = lock(id, input.version());
		requireStatus(row, "CLAIMED");
		closeAndReopen(row, actor, "REJECTED", input.note());
		return response(row(id), null, null, true);
	}

	Offer assign(UUID id, UUID actor, Assignment input) {
		ScheduleEvent event = lockEvent(id);
		Row row = lock(id, input.version());
		if (!row.actionable()) throw new ApiException(ErrorCode.SUBSTITUTION_ALREADY_DECIDED, "Request closed.");
		requireEligible(row, input.staffId());
		transfer(event, row.originalStaffId(), input.staffId());
		decide(row, actor, "APPROVED", input.staffId(), input.note());
		return response(row(id), null, null, true);
	}

	Offer close(UUID id, UUID actor, Action input) {
		lockEvent(id);
		Row row = lock(id, input.version());
		if (!row.actionable()) throw new ApiException(ErrorCode.SUBSTITUTION_ALREADY_DECIDED, "Request closed.");
		decide(row, actor, "CANCELLED", row.substituteStaffId(), input.note());
		return response(row(id), null, null, true);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Notice> notification(UUID id, int version) {
		Row row = this.jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(SubstitutionManagement::map).optional().orElse(null);
		if (row == null || row.version() != version || !row.status().equals("OPEN") || !row.actionable()) return Optional.empty();
		var candidates = this.staff.findAll(true);
		var checks = this.eligibility.assess(List.of(row), candidates);
		var ids = candidates.stream().filter((s) -> checks.get(new Key(id, s.id())).available()).map(StaffProfiles.StaffMember::id).toList();
		return Optional.of(new Notice(id, version, row.title(), row.rooms(), row.startsAt(), row.endsAt(),
				this.staff.findAccountIds(ids).values().stream().distinct().toList()));
	}

	private void requireEligible(Row row, UUID candidate) {
		// Serializes with other schedule assignments and with staff time-off edits.
		var event = this.events.findById(row.eventId()).orElseThrow(SubstitutionManagement::notFound);
		this.locks.lock(java.util.stream.Stream.concat(event.getStaff().stream().map(EventStaffAssignment::staffId),
				java.util.stream.Stream.of(candidate)).toList(), event.getResources().stream().map(EventResourceAssignment::resourceId).toList());
		this.jdbc.sql("SELECT id FROM staff_profile WHERE id = :id FOR UPDATE").param("id", candidate).query(UUID.class).optional();
		if (!row.actionable()) throw new ApiException(ErrorCode.SUBSTITUTION_ALREADY_DECIDED, "The event has started or changed.");
		var check = this.eligibility.assess(List.of(row), this.staff.findAllById(List.of(candidate))).get(new Key(row.id(), candidate));
		if (check == null || !check.available()) throw new ApiException(ErrorCode.SUBSTITUTION_NOT_ELIGIBLE,
				"The trainer cannot take this event.", Map.of("reason", check == null ? "INACTIVE" : check.reason()));
	}

	private void transfer(ScheduleEvent event, UUID original, UUID candidate) {
		var staff = new java.util.ArrayList<>(event.getStaff().stream()
			.filter((s) -> !s.staffId().equals(original) && !s.staffId().equals(candidate))
			.map((s) -> new StaffAssignmentRequest(s.staffId(), s.role())).toList());
		staff.add(new StaffAssignmentRequest(candidate, StaffRole.LEAD));
		var rooms = event.getResources().stream().map((r) -> new ResourceAssignmentRequest(r.resourceId(), r.blocksWholeRoom())).toList();
		this.planner.changeAssignments(event.getId(), new ChangeAssignmentsRequest(event.getVersion(), AssignmentScope.THIS_EVENT, staff, rooms));
	}

	private void closeAndReopen(Row row, UUID actor, String status, @Nullable String note) {
		decide(row, actor, status, row.substituteStaffId(), note);
		UUID replacement = this.jdbc.sql("""
				INSERT INTO substitution_request (event_id, original_staff_id, requested_by, reason, time_off_id)
				SELECT event_id, original_staff_id, requested_by, reason, time_off_id FROM substitution_request WHERE id = :id RETURNING id
				""").param("id", row.id()).query(UUID.class).single();
		this.publisher.publishEvent(new SubstitutionOpened(replacement, 0));
	}

	@EventListener
	void moved(ScheduleEventChanged event) {
		if (!event.moved()) return;
		this.jdbc.sql("""
				UPDATE substitution_request r SET status='EXPIRED', version=version+1,
				decision_note='Termin przeniesiono poza okres nieobecności.'
				WHERE r.event_id=:event AND r.time_off_id IS NOT NULL AND r.status IN ('OPEN','CLAIMED')
				AND NOT EXISTS (SELECT 1 FROM staff_time_off t WHERE t.id=r.time_off_id AND t.status='APPROVED'
				  AND :starts<t.ends_at AND :ends>t.starts_at)
				""").param("event", event.eventId()).param("starts", Timestamp.from(event.startsAt()))
			.param("ends", Timestamp.from(event.endsAt())).update();
	}

	@EventListener
	void cancelled(ScheduleEventCancelled event) {
		this.jdbc.sql("""
				UPDATE substitution_request r SET status='CANCELLED', version=version+1,
				decided_by=:actor, decided_at=now(), decision_note='Zajęcia zostały odwołane.'
				WHERE r.event_id=:event AND r.status IN ('OPEN','CLAIMED')
				AND EXISTS (SELECT 1 FROM schedule_event e WHERE e.id=r.event_id AND e.status='CANCELLED')
				""").param("event", event.eventId()).param("actor", event.cancelledBy(), java.sql.Types.OTHER).update();
	}

	private void decide(Row row, UUID actor, String status, @Nullable UUID candidate, @Nullable String note) {
		this.jdbc.sql("""
				UPDATE substitution_request SET status = :status, substitute_staff_id = :candidate,
				claimed_at = CASE WHEN CAST(:candidate AS UUID) IS NULL THEN claimed_at ELSE COALESCE(claimed_at, now()) END,
				decided_by = :actor, decided_at = now(), decision_note = :note, version = version + 1 WHERE id = :id
				""").param("id", row.id()).param("status", status).param("candidate", candidate, java.sql.Types.OTHER)
			.param("actor", actor).param("note", trim(note)).update();
		record(row, actor, status, candidate, note);
	}

	private void record(Row row, UUID actor, String status, @Nullable UUID candidate, @Nullable String note) {
		this.audit.append(new AuditLog.Entry(actor, "scheduling", "substitution_request", row.id(), status,
				Map.of("status", new AuditLog.Change(row.status(), status),
						"substituteStaffId", new AuditLog.Change(row.substituteStaffId(), candidate)), trim(note)));
	}

	private ScheduleEvent lockEvent(UUID id) {
		UUID eventId = row(id).eventId();
		return this.events.findByIdForUpdate(eventId).orElseThrow(SubstitutionManagement::notFound);
	}

	private Row lock(UUID id, int version) {
		Row row = this.jdbc.sql(SELECT + " WHERE r.id = :id FOR UPDATE OF r").param("id", id).query(SubstitutionManagement::map).single();
		if (row.version() != version) throw new ApiException(ErrorCode.VERSION_CONFLICT, "Refresh the request.");
		if (!row.actionable()) throw new ApiException(ErrorCode.SUBSTITUTION_ALREADY_DECIDED, "The event or request is no longer available.");
		return row;
	}

	private static void requireStatus(Row row, String status) {
		if (!row.status().equals(status)) throw new ApiException(ErrorCode.SUBSTITUTION_ALREADY_DECIDED, "The request changed.");
	}

	private List<Row> rows(Instant from, Instant to) {
		return this.jdbc.sql(SELECT + " WHERE e.starts_at < :to AND e.ends_at > :from ORDER BY e.starts_at, r.created_at, r.id")
			.param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query(SubstitutionManagement::map).list();
	}

	private Row row(UUID id) {
		return this.jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(SubstitutionManagement::map).optional().orElseThrow(SubstitutionManagement::notFound);
	}

	@Transactional(readOnly = true)
	Offer adminOffer(UUID id) { return response(row(id), null, null, true); }

	private Offer response(Row r, @Nullable UUID viewer, @Nullable Result check, boolean admin) {
		return response(r, viewer, check, admin, this.studio.substitutionNeedsApproval());
	}

	private Offer response(Row r, @Nullable UUID viewer, @Nullable Result check, boolean admin, boolean approval) {
		String status = (r.status().equals("OPEN") || r.status().equals("CLAIMED")) && !r.actionable() ? "EXPIRED" : r.status();
		String reason = !r.actionable() || !r.status().equals("OPEN") ? "CLOSED" : check == null ? null : check.reason();
		return new Offer(r.id(), r.eventId(), r.version(), r.eventVersion(), status, r.kind(), r.title(), r.rooms(), r.startsAt(), r.endsAt(),
				r.originalStaffId(), r.originalName(), r.substituteStaffId(), r.substituteName(), approval,
				viewer != null && r.status().equals("OPEN") && r.actionable() && check != null && check.available(),
				viewer != null && viewer.equals(r.substituteStaffId()) && r.status().equals("CLAIMED") && r.actionable(),
				reason, check == null ? List.of() : check.warnings(), admin || viewer != null && viewer.equals(r.substituteStaffId()) ? r.decisionNote() : null);
	}

	private static Row map(ResultSet rs, int index) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class), rs.getInt("version"), rs.getInt("event_version"),
				rs.getString("status"), rs.getString("event_status"), rs.getString("kind"), rs.getString("title"), rs.getString("rooms"),
				rs.getObject("service_type_id", UUID.class), rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
				rs.getObject("original_staff_id", UUID.class), rs.getString("original_name"), rs.getObject("substitute_staff_id", UUID.class),
				rs.getString("substitute_name"), rs.getBoolean("current_lead"), rs.getString("decision_note"));
	}

	private static @Nullable String trim(@Nullable String value) { return value == null || value.isBlank() ? null : value.strip(); }
	private static ApiException notFound() { return new ApiException(ErrorCode.NOT_FOUND, "Substitution request not found."); }
}
