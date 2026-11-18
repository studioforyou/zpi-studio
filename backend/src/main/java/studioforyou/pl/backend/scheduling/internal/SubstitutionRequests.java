package studioforyou.pl.backend.scheduling.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Requests reserve no resources and leave the event's staff and bookings unchanged. */
@Service
@Transactional
class SubstitutionRequests {

	private final JdbcClient jdbc;
	private final ScheduleEventRepository events;
	private final StaffProfiles staff;

	SubstitutionRequests(JdbcClient jdbc, ScheduleEventRepository events, StaffProfiles staff) {
		this.jdbc = jdbc;
		this.events = events;
		this.staff = staff;
	}

	@Schema(name = "CreateOwnSubstitutionRequest")
	record CreateRequest(@Size(max = 1000) @Nullable String reason) {
	}

	@Schema(name = "OwnSubstitutionRequest")
	record RequestResponse(UUID id, UUID eventId, String status, @Nullable String reason, Instant createdAt,
			boolean canCancel) {
	}

	@Schema(name = "OwnSubstitutionState")
	record RequestState(boolean canRequest, @Nullable RequestResponse request) {
	}

	private record StoredRequest(UUID id, UUID eventId, UUID originalStaffId, String status,
			@Nullable String reason, Instant createdAt) {
		RequestResponse response(UUID viewer, boolean editable) {
			boolean mine = this.originalStaffId.equals(viewer);
			return new RequestResponse(this.id, this.eventId, this.status, mine ? this.reason : null,
					this.createdAt, mine && editable && this.status.equals("OPEN"));
		}
	}

	@Transactional(readOnly = true)
	List<RequestResponse> list(UUID staffId, Instant from, Instant to) {
		boolean editable = active(staffId);
		return this.jdbc.sql("""
				SELECT r.* FROM substitution_request r
				JOIN schedule_event e ON e.id = r.event_id
				JOIN event_staff s ON s.event_id = e.id AND s.staff_id = r.original_staff_id AND s.role = 'LEAD'
				WHERE r.original_staff_id = :staff AND r.status IN ('OPEN', 'CLAIMED')
				AND e.status = 'SCHEDULED' AND e.starts_at > :now
				AND e.starts_at < :to AND e.ends_at > :from
				ORDER BY e.starts_at, r.created_at, r.id
				""").param("staff", staffId).param("now", Timestamp.from(Instant.now()))
			.param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
			.query(SubstitutionRequests::map).list().stream().map((r) -> r.response(staffId, editable)).toList();
	}

	@Transactional(readOnly = true)
	RequestState state(UUID eventId, UUID staffId) {
		ScheduleEvent event = event(eventId, staffId, false);
		StoredRequest request = activeRequest(eventId);
		boolean editable = editable(event) && leads(event, staffId) && active(staffId);
		return new RequestState(editable && request == null,
				(request == null) ? null : request.response(staffId, editable));
	}

	RequestResponse create(UUID eventId, UUID staffId, UUID accountId, CreateRequest input) {
		ScheduleEvent event = event(eventId, staffId, true);
		requireEditable(event, staffId);
		// The event row serializes create/cancel with changes of its time, status and staff.
		// An old request must not prevent a new lead asking after a separate reassignment.
		this.jdbc.sql("""
				UPDATE substitution_request r SET status = 'EXPIRED'
				WHERE event_id = :event AND status IN ('OPEN', 'CLAIMED')
				AND NOT EXISTS (SELECT 1 FROM event_staff s WHERE s.event_id = r.event_id
				AND s.staff_id = r.original_staff_id AND s.role = 'LEAD')
				""").param("event", eventId).update();
		if (activeRequest(eventId) != null) {
			throw new ApiException(ErrorCode.SUBSTITUTION_ALREADY_REQUESTED,
					"This event already has an active substitution request.");
		}
		String reason = input.reason();
		return this.jdbc.sql("""
				INSERT INTO substitution_request (event_id, original_staff_id, requested_by, reason)
				VALUES (:event, :staff, :account, :reason) RETURNING *
				""").param("event", eventId).param("staff", staffId).param("account", accountId)
			.param("reason", (reason == null || reason.isBlank()) ? null : reason.strip())
			.query(SubstitutionRequests::map).single().response(staffId, true);
	}

	void cancel(UUID requestId, UUID staffId) {
		StoredRequest request = this.jdbc.sql("SELECT * FROM substitution_request WHERE id = :id")
			.param("id", requestId).query(SubstitutionRequests::map).optional()
			.filter((r) -> r.originalStaffId.equals(staffId)).orElseThrow(SubstitutionRequests::notFound);
		ScheduleEvent event = event(request.eventId, staffId, true);
		requireEditable(event, staffId);
		// Always lock event, then request; a later claiming workflow must use the same order.
		request = this.jdbc.sql("SELECT * FROM substitution_request WHERE id = :id FOR UPDATE")
			.param("id", requestId).query(SubstitutionRequests::map).single();
		if (!request.status.equals("OPEN")) {
			throw new ApiException(ErrorCode.SUBSTITUTION_NOT_OPEN,
					"Only an open substitution request can be withdrawn.");
		}
		this.jdbc.sql("UPDATE substitution_request SET status = 'CANCELLED' WHERE id = :id")
			.param("id", requestId).update();
	}

	private ScheduleEvent event(UUID eventId, UUID staffId, boolean lock) {
		return (lock ? this.events.findByIdForUpdate(eventId) : this.events.findById(eventId))
			.filter((e) -> e.getKind() != EventKind.BLOCK)
			.filter((e) -> e.getStaff().stream().anyMatch((s) -> s.staffId().equals(staffId)))
			.orElseThrow(SubstitutionRequests::notFound);
	}

	private boolean active(UUID staffId) {
		return this.staff.findAllById(List.of(staffId)).stream().anyMatch(StaffProfiles.StaffMember::active);
	}

	private void requireEditable(ScheduleEvent event, UUID staffId) {
		if (!active(staffId)) {
			throw new ApiException(ErrorCode.STAFF_UNAVAILABLE, "The trainer is not active.");
		}
		if (!leads(event, staffId)) {
			throw new ApiException(ErrorCode.ACCESS_DENIED, "Only an event's lead may request a substitute.");
		}
		if (!editable(event)) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE,
					"Substitution requests are for upcoming scheduled events only.");
		}
	}

	private static boolean leads(ScheduleEvent event, UUID staffId) {
		return event.getStaff().stream().anyMatch((s) -> s.staffId().equals(staffId) && s.role() == StaffRole.LEAD);
	}

	private static boolean editable(ScheduleEvent event) {
		return event.getStatus() == EventStatus.SCHEDULED && event.getStartsAt().isAfter(Instant.now());
	}

	private @Nullable StoredRequest activeRequest(UUID eventId) {
		return this.jdbc.sql("""
				SELECT r.* FROM substitution_request r
				JOIN event_staff s ON s.event_id = r.event_id AND s.staff_id = r.original_staff_id AND s.role = 'LEAD'
				WHERE r.event_id = :event AND r.status IN ('OPEN', 'CLAIMED')
				""").param("event", eventId).query(SubstitutionRequests::map).optional().orElse(null);
	}

	private static StoredRequest map(ResultSet row, int index) throws SQLException {
		return new StoredRequest(row.getObject("id", UUID.class), row.getObject("event_id", UUID.class),
				row.getObject("original_staff_id", UUID.class), row.getString("status"), row.getString("reason"),
				row.getTimestamp("created_at").toInstant());
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Event or substitution request not found.");
	}

}
