package studioforyou.pl.backend.scheduling.internal;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.scheduling.SubstitutionOpened;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.TimeOffSubstitutions;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A saved selection belongs to the absence; offers are opened only after its approval. */
@Service
@Transactional
class TimeOffSubstitutionPlanning implements TimeOffSubstitutions {

	private final JdbcClient jdbc;
	private final ApplicationEventPublisher publisher;
	private final SubstitutionManagement requests;
	private final SubstitutionEligibility eligibility;
	private final StaffProfiles staff;
	private final ScheduleLocks scheduleLocks;
	private final AuditLog audit;

	TimeOffSubstitutionPlanning(JdbcClient jdbc, ApplicationEventPublisher publisher, SubstitutionManagement requests,
			SubstitutionEligibility eligibility, StaffProfiles staff, ScheduleLocks scheduleLocks, AuditLog audit) {
		this.jdbc = jdbc;
		this.publisher = publisher;
		this.requests = requests;
		this.eligibility = eligibility;
		this.staff = staff;
		this.scheduleLocks = scheduleLocks;
		this.audit = audit;
	}

	private record Absence(UUID id, UUID staffId, Instant from, Instant to, String status, int version) { }

	private record Session(Lesson item, @Nullable UUID serviceTypeId, boolean lead, String eventStatus,
			@Nullable UUID requestTimeOffId, String originalName) {
		boolean live() {
			return this.lead && coveredKind(this.item.kind()) && this.eventStatus.equals("SCHEDULED")
					&& this.item.startsAt().isAfter(Instant.now());
		}

		SubstitutionManagement.Row eligibilityRow(UUID original) {
			Lesson r = this.item;
			return new SubstitutionManagement.Row(r.requestId() == null ? r.eventId() : r.requestId(), r.eventId(),
					r.requestVersion() == null ? 0 : r.requestVersion(), r.eventVersion(), "OPEN", this.eventStatus,
					r.kind(), r.title(), r.rooms(), this.serviceTypeId, r.startsAt(), r.endsAt(), original,
					this.originalName, r.substituteStaffId(), r.substituteTrainer(), this.lead, null);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Plan preview(UUID staffId, Instant from, Instant to, @Nullable UUID timeOffId) {
		if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Choose a valid time-off period of at most 366 days.");
		}
		Absence absence = timeOffId == null ? null : absence(timeOffId, false);
		if (absence != null && !absence.staffId().equals(staffId)) {
			throw notFound();
		}
		return new Plan(timeOffId, absence == null ? 0 : absence.version(), absence == null ? "DRAFT" : absence.status(),
				rows(staffId, from, to, timeOffId).stream().map(Session::item).toList());
	}

	@Override
	@Transactional(readOnly = true)
	public Plan plan(UUID timeOffId, @Nullable UUID viewerStaffId) {
		Absence a = absence(timeOffId, false);
		if (viewerStaffId != null && !a.staffId().equals(viewerStaffId)) {
			throw notFound();
		}
		return preview(a.staffId(), a.from(), a.to(), a.id());
	}

	@Override
	public void prepare(UUID staffId, Instant from, Instant to, @Nullable List<UUID> selected, @Nullable UUID timeOffId) {
		if (selected == null) {
			return;
		}
		List<UUID> locks = new ArrayList<>(selected);
		if (timeOffId != null) locks.addAll(ownedEventIds(timeOffId));
		lockEvents(locks);
		Set<UUID> valid = new HashSet<>();
		for (Session row : rows(staffId, from, to, null)) {
			if (row.live()) valid.add(row.item().eventId());
		}
		if (new HashSet<>(selected).size() != selected.size() || !valid.containsAll(selected)) {
			throw changed();
		}
	}

	@Override
	public void replace(UUID timeOffId, UUID staffId, Instant from, Instant to, List<UUID> selected,
			boolean approved, UUID actorId) {
		Set<UUID> wanted = Set.copyOf(selected);
		lockEvents(ownedEventIds(timeOffId));
		this.jdbc.sql("UPDATE time_off_substitution SET selected = FALSE WHERE time_off_id = :id")
			.param("id", timeOffId).update();
		for (Session row : rows(staffId, from, to, timeOffId)) {
			this.jdbc.sql("""
					INSERT INTO time_off_substitution(time_off_id, event_id, selected) VALUES (:id, :event, :selected)
					ON CONFLICT (time_off_id, event_id) DO UPDATE SET selected = EXCLUDED.selected
					""").param("id", timeOffId).param("event", row.item().eventId())
				.param("selected", wanted.contains(row.item().eventId())).update();
		}
		closeOwned(timeOffId, wanted, actorId);
		if (approved) activate(timeOffId, actorId);
	}

	@Override
	public void activate(UUID timeOffId, UUID actorId) {
		Absence a = absence(timeOffId, true);
		if (!a.status().equals("APPROVED")) {
			throw new ApiException(ErrorCode.TIME_OFF_ALREADY_DECIDED, "Approve the time off before opening offers.");
		}
		List<UUID> selected = this.jdbc.sql("SELECT event_id FROM time_off_substitution WHERE time_off_id = :id AND selected")
			.param("id", timeOffId).query(UUID.class).list();
		lockEvents(selected);
		for (Session row : rows(a.staffId(), a.from(), a.to(), a.id())) {
			if (row.live() && selected.contains(row.item().eventId())) ensureRequest(a, row, actorId);
		}
	}

	@Override
	public void close(UUID timeOffId, UUID actorId) {
		lockEvents(ownedEventIds(timeOffId));
		closeOwned(timeOffId, Set.of(), actorId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Candidate> candidates(UUID timeOffId, List<UUID> eventIds) {
		Absence a = absence(timeOffId, false);
		List<Session> selected = selected(a, eventIds);
		var trainers = this.staff.findAll(true).stream().filter(s -> !s.id().equals(a.staffId())).toList();
		var checks = this.eligibility.assess(selected.stream().map(s -> s.eligibilityRow(a.staffId())).toList(), trainers);
		boolean overlapping = overlaps(selected);
		return trainers.stream().map(trainer -> {
			String reason = overlapping ? "SELECTED_EVENTS_OVERLAP" : null;
			Set<String> warnings = new java.util.LinkedHashSet<>();
			for (Session session : selected) {
				var check = checks.get(new SubstitutionEligibility.Key(session.eligibilityRow(a.staffId()).id(), trainer.id()));
				if (check == null) reason = "INACTIVE";
				else {
					if (!check.available() && reason == null) reason = check.reason();
					warnings.addAll(check.warnings());
				}
			}
			return new Candidate(trainer.id(), trainer.displayName(), reason == null, reason, List.copyOf(warnings));
		}).toList();
	}

	@Override
	public Plan decide(UUID timeOffId, Batch input, UUID actorId, boolean approveClaims) {
		Absence a = absence(timeOffId, true);
		if (a.version() != input.version()) throw changed();
		if (!a.status().equals("APPROVED") || !a.to().isAfter(Instant.now())) {
			throw new ApiException(ErrorCode.TIME_OFF_ALREADY_DECIDED, "This time off is not active and approved.");
		}
		List<UUID> ids = input.items().stream().map(EventVersion::eventId).toList();
		lockEvents(ids);
		List<Session> sessions = selected(a, ids);
		Map<UUID, Session> byId = new HashMap<>();
		sessions.forEach(s -> byId.put(s.item().eventId(), s));
		for (EventVersion expected : input.items()) {
			Lesson current = byId.get(expected.eventId()).item();
			if (current.eventVersion() != expected.eventVersion()
					|| !java.util.Objects.equals(current.requestId(), expected.requestId())
					|| !java.util.Objects.equals(current.requestVersion(), expected.requestVersion())) throw changed();
			if (approveClaims && !current.status().equals("CLAIMED")) throw changed();
			if (!approveClaims && current.status().equals("CLAIMED")) throw changed();
		}
		if (!approveClaims && (input.staffId() == null || overlaps(sessions))) {
			throw new ApiException(ErrorCode.SUBSTITUTION_NOT_ELIGIBLE, "Choose a trainer free for all selected sessions.");
		}
		Set<UUID> trainers = new HashSet<>();
		if (input.staffId() != null) trainers.add(input.staffId());
		if (approveClaims) {
			for (Session session : sessions) {
				if (session.item().substituteStaffId() == null) throw changed();
				trainers.add(session.item().substituteStaffId());
			}
		}
		var assigned = this.jdbc.sql("SELECT staff_id FROM event_staff WHERE event_id IN (:ids)")
			.param("ids", ids).query(UUID.class).list();
		Set<UUID> locks = new HashSet<>(assigned);
		locks.addAll(trainers);
		var rooms = this.jdbc.sql("SELECT resource_id FROM event_resource WHERE event_id IN (:ids)")
			.param("ids", ids).query(UUID.class).list();
		this.scheduleLocks.lock(locks, rooms);
		this.jdbc.sql("SELECT id FROM staff_profile WHERE id IN (:ids) ORDER BY id FOR UPDATE")
			.param("ids", trainers).query(UUID.class).list();
		for (Session session : sessions) {
			UUID requestId = ensureRequest(a, session, actorId);
			var offer = this.requests.adminOffer(requestId);
			if (approveClaims) {
				this.requests.approve(requestId, actorId, new SubstitutionDtos.Action(offer.version(), input.note()));
			}
			else {
				this.requests.assign(requestId, actorId,
						new SubstitutionDtos.Assignment(offer.version(), input.staffId(), input.note()));
			}
		}
		this.jdbc.sql("UPDATE staff_time_off SET version = version + 1 WHERE id = :id").param("id", timeOffId).update();
		return plan(timeOffId, null);
	}

	private List<Session> selected(Absence a, List<UUID> ids) {
		Set<UUID> wanted = new HashSet<>(ids);
		var rows = rows(a.staffId(), a.from(), a.to(), a.id()).stream()
			.filter(s -> wanted.contains(s.item().eventId()) && s.live()).toList();
		if (ids.isEmpty() || wanted.size() != ids.size() || rows.size() != ids.size()) throw changed();
		return rows;
	}

	private UUID ensureRequest(Absence a, Session row, UUID actorId) {
		UUID eventId = row.item().eventId();
		this.jdbc.sql("""
				UPDATE substitution_request r SET status = 'EXPIRED', version = version + 1
				WHERE event_id = :event AND status IN ('OPEN', 'CLAIMED') AND NOT EXISTS (
				 SELECT 1 FROM event_staff s WHERE s.event_id = r.event_id AND s.staff_id = r.original_staff_id AND s.role = 'LEAD')
				""").param("event", eventId).update();
		var existing = this.jdbc.sql("SELECT id FROM substitution_request WHERE event_id = :event AND status IN ('OPEN','CLAIMED')")
			.param("event", eventId).query(UUID.class).optional();
		if (existing.isPresent()) return existing.get();
		UUID id = this.jdbc.sql("""
				INSERT INTO substitution_request(event_id, original_staff_id, requested_by, time_off_id)
				VALUES (:event, :staff, :actor, :leave) RETURNING id
				""").param("event", eventId).param("staff", a.staffId()).param("actor", actorId).param("leave", a.id())
			.query(UUID.class).single();
		this.jdbc.sql("""
				INSERT INTO time_off_substitution(time_off_id, event_id, selected) VALUES (:id, :event, TRUE)
				ON CONFLICT (time_off_id, event_id) DO UPDATE SET selected = TRUE
				""").param("id", a.id()).param("event", eventId).update();
		this.publisher.publishEvent(new SubstitutionOpened(id, 0));
		return id;
	}

	private void closeOwned(UUID timeOffId, Set<UUID> keep, UUID actorId) {
		var rows = this.jdbc.sql("SELECT id, event_id, status FROM substitution_request WHERE time_off_id = :id AND status IN ('OPEN','CLAIMED') ORDER BY id")
			.param("id", timeOffId).query().listOfRows();
		for (var row : rows) {
			if (keep.contains((UUID) row.get("event_id"))) continue;
			UUID id = (UUID) row.get("id");
			this.jdbc.sql("""
					UPDATE substitution_request SET status = 'CANCELLED', version = version + 1,
					decided_by = :actor, decided_at = now(), decision_note = 'Plan nieobecności został zmieniony.' WHERE id = :id
					""").param("id", id).param("actor", actorId).update();
			this.audit.append(new AuditLog.Entry(actorId, "scheduling", "substitution_request", id, "CANCELLED",
					Map.of("status", new AuditLog.Change(row.get("status"), "CANCELLED")), null));
		}
	}

	private List<UUID> ownedEventIds(UUID timeOffId) {
		return this.jdbc.sql("SELECT DISTINCT event_id FROM substitution_request WHERE time_off_id = :id AND status IN ('OPEN','CLAIMED')")
			.param("id", timeOffId).query(UUID.class).list();
	}

	private void lockEvents(List<UUID> ids) {
		if (!ids.isEmpty()) this.jdbc.sql("SELECT id FROM schedule_event WHERE id IN (:ids) ORDER BY id FOR UPDATE")
			.param("ids", ids).query(UUID.class).list();
	}

	private Absence absence(UUID id, boolean lock) {
		return this.jdbc.sql("SELECT id, staff_id, starts_at, ends_at, status, version FROM staff_time_off WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
			.param("id", id).query((rs, n) -> new Absence(id, rs.getObject("staff_id", UUID.class),
					rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(), rs.getString("status"), rs.getInt("version")))
			.optional().orElseThrow(TimeOffSubstitutionPlanning::notFound);
	}

	private static boolean overlaps(List<Session> sessions) {
		var sorted = sessions.stream().sorted(Comparator.comparing(s -> s.item().startsAt())).toList();
		for (int i = 1; i < sorted.size(); i++) {
			if (sorted.get(i).item().startsAt().isBefore(sorted.get(i - 1).item().endsAt())) return true;
		}
		return false;
	}

	private static boolean coveredKind(String kind) {
		return kind.equals("CLASS") || kind.equals("PERSONAL_TRAINING");
	}

	private List<Session> rows(UUID staffId, Instant from, Instant to, @Nullable UUID leaveId) {
		return this.jdbc.sql("""
				SELECT e.id, e.version, e.kind, e.status AS event_status, e.service_type_id, e.starts_at, e.ends_at,
				 CASE WHEN e.kind = 'PERSONAL_TRAINING' THEN COALESCE(t.name, 'Trening personalny')
				 ELSE COALESCE(e.title, t.name, 'Zajęcia') END AS title,
				 COALESCE((SELECT string_agg(room.name, ', ' ORDER BY room.name) FROM event_resource er
				 JOIN resource room ON room.id = er.resource_id WHERE er.event_id = e.id), 'Studio') AS rooms,
				 EXISTS (SELECT 1 FROM event_staff es WHERE es.event_id = e.id AND es.staff_id = :staff AND es.role = 'LEAD') AS lead,
				 COALESCE(chosen.selected, TRUE) AS selected,
				 r.id AS request_id, r.version AS request_version, r.status AS request_status, r.time_off_id AS request_leave,
				 r.substitute_staff_id, NULLIF(concat_ws(' ', candidate.first_name, candidate.last_name), '') AS substitute_name,
				 concat_ws(' ', owner.first_name, owner.last_name) AS original_name
				FROM schedule_event e
				LEFT JOIN service_type t ON t.id = e.service_type_id
				JOIN staff_profile owner ON owner.id = :staff
				LEFT JOIN time_off_substitution chosen ON chosen.time_off_id = CAST(:leave AS UUID) AND chosen.event_id = e.id
				LEFT JOIN LATERAL (SELECT r.* FROM substitution_request r WHERE r.event_id = e.id AND r.original_staff_id = :staff
				 ORDER BY CASE WHEN r.status IN ('OPEN','CLAIMED') THEN 0 ELSE 1 END, r.created_at DESC, r.id DESC LIMIT 1) r ON TRUE
				LEFT JOIN staff_profile candidate ON candidate.id = r.substitute_staff_id
				WHERE e.kind <> 'BLOCK' AND e.starts_at < :to AND e.ends_at > :from
				 AND (EXISTS (SELECT 1 FROM event_staff es WHERE es.event_id = e.id AND es.staff_id = :staff AND es.role = 'LEAD')
				 OR chosen.event_id IS NOT NULL)
				ORDER BY e.starts_at, e.id
				""").param("staff", staffId).param("leave", leaveId, java.sql.Types.OTHER)
			.param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query((rs, n) -> {
				boolean lead = rs.getBoolean("lead");
				Instant starts = rs.getTimestamp("starts_at").toInstant();
				String eventStatus = rs.getString("event_status");
				boolean live = lead && eventStatus.equals("SCHEDULED") && starts.isAfter(Instant.now());
				boolean supported = coveredKind(rs.getString("kind"));
				String requestStatus = rs.getString("request_status");
				boolean active = "OPEN".equals(requestStatus) || "CLAIMED".equals(requestStatus);
				String status = live && !supported ? "MANUAL" : live ? active ? requestStatus : "NOT_REQUESTED"
						: "APPROVED".equals(requestStatus) ? "APPROVED" : "CLOSED";
				UUID requestLeave = rs.getObject("request_leave", UUID.class);
				boolean selectable = live && supported && (!active || leaveId != null && leaveId.equals(requestLeave));
				boolean visibleRequest = active || "APPROVED".equals(status);
				Lesson item = new Lesson(rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("title"),
						rs.getString("kind"), rs.getString("rooms"), starts, rs.getTimestamp("ends_at").toInstant(),
						selectable, supported && rs.getBoolean("selected"), visibleRequest ? rs.getObject("request_id", UUID.class) : null,
						visibleRequest ? rs.getInt("request_version") : null, status,
						rs.getObject("substitute_staff_id", UUID.class), rs.getString("substitute_name"));
				return new Session(item, rs.getObject("service_type_id", UUID.class), lead, eventStatus, requestLeave,
						rs.getString("original_name"));
			}).list();
	}

	private static ApiException changed() {
		return new ApiException(ErrorCode.VERSION_CONFLICT, "The absence or its sessions changed. Refresh the selection.");
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Time off not found.");
	}
}
