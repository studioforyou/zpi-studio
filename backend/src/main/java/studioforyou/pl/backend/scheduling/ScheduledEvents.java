package studioforyou.pl.backend.scheduling;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;

/**
 * Events of the schedule for other modules: reports, staff access to an event's participants, and
 * recording that an event with participants took place.
 */
@Repository
public class ScheduledEvents {

	private static final RowMapper<AttendedEvent> ATTENDED_EVENT = (rs, row) -> new AttendedEvent(
			rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"), instant(rs, "starts_at"),
			instant(rs, "ends_at"));

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	public ScheduledEvents(JdbcClient jdbc, AuditLog auditLog) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
	}

	/** Events starting in {@code [from, to)} that were not cancelled. */
	public List<UUID> findIdsStartingBetween(Instant from, Instant to) {
		return jdbc.sql("""
				SELECT id
				FROM schedule_event
				WHERE starts_at >= :from AND starts_at < :to AND status <> 'CANCELLED'
				""")
			.param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
			.param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
			.query(UUID.class)
			.list();
	}

	/**
	 * Whether the staff member runs or assists the event, whatever its status and visibility.
	 * Blocks have no participants, so they never count.
	 */
	public boolean isAssigned(UUID eventId, UUID staffId) {
		return jdbc.sql("""
				SELECT EXISTS (
				  SELECT 1
				  FROM event_staff s
				  JOIN schedule_event e ON e.id = s.event_id
				  WHERE s.event_id = :eventId AND s.staff_id = :staffId AND e.kind <> 'BLOCK'
				)
				""")
			.param("eventId", eventId)
			.param("staffId", staffId)
			.query(Boolean.class)
			.single();
	}

	/** An event that can have participants, whatever its status and visibility; empty for a block. */
	public Optional<AttendedEvent> findAttended(UUID eventId) {
		return jdbc.sql("SELECT id, kind, status, starts_at, ends_at FROM schedule_event WHERE id = :id AND kind <> 'BLOCK'")
			.param("id", eventId)
			.query(ATTENDED_EVENT)
			.optional();
	}

	/**
	 * Like {@link #findAttended(UUID)}, holding the event's row lock, which schedule edits and
	 * bookings also take, until the caller's transaction ends.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<AttendedEvent> lockAttended(UUID eventId) {
		return jdbc.sql("""
				SELECT id, kind, status, starts_at, ends_at FROM schedule_event
				WHERE id = :id AND kind <> 'BLOCK' FOR UPDATE
				""")
			.param("id", eventId)
			.query(ATTENDED_EVENT)
			.optional();
	}

	/**
	 * Records that a scheduled event took place: it becomes {@code COMPLETED}, so it can no longer be
	 * edited, and the audit log keeps who did it. Does nothing for an event that is not
	 * {@code SCHEDULED}. The caller holds the event's lock from {@link #lockAttended(UUID)}.
	 *
	 * @return whether the event was completed now
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean markCompleted(UUID eventId, UUID actorAccountId) {
		boolean completed = jdbc.sql("""
				UPDATE schedule_event SET status = 'COMPLETED', updated_at = now(), version = version + 1
				WHERE id = :id AND kind <> 'BLOCK' AND status = 'SCHEDULED'
				""")
			.param("id", eventId)
			.update() == 1;
		if (completed) {
			auditLog.append(new AuditLog.Entry(actorAccountId, "scheduling", "schedule_event", eventId,
					"EVENT_COMPLETED", Map.of("status", new AuditLog.Change("SCHEDULED", "COMPLETED")), null));
		}
		return completed;
	}

	/** Booking calls this only after the last participant cancels, holding the occurrence lock. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelPersonal(UUID eventId, UUID actorId) {
		if (jdbc.sql("""
				UPDATE schedule_event SET status = 'CANCELLED', cancel_reason = 'OTHER',
				cancel_note = 'Odwołane przez klienta', cancelled_by = :actor, cancelled_at = now(),
				updated_at = now(), version = version + 1
				WHERE id = :id AND kind = 'PERSONAL_TRAINING' AND status = 'SCHEDULED'
				""").param("id", eventId).param("actor", actorId).update() == 1) {
			auditLog.append(new AuditLog.Entry(actorId, "scheduling", "schedule_event", eventId, "EVENT_CANCELLED",
					Map.of("status", new AuditLog.Change("SCHEDULED", "CANCELLED")), "Odwołane przez klienta"));
		}
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

	/**
	 * An event with participants as their attendance needs it.
	 *
	 * @param kind e.g. {@code CLASS} or {@code PERSONAL_TRAINING}
	 * @param status {@code SCHEDULED}, {@code COMPLETED} or {@code CANCELLED}
	 */
	public record AttendedEvent(UUID id, String kind, String status, Instant startsAt, Instant endsAt) {

		public boolean cancelled() {
			return "CANCELLED".equals(this.status);
		}

	}

}
