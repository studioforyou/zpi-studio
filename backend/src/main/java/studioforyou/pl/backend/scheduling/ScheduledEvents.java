package studioforyou.pl.backend.scheduling;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;

/**
 * Events of the schedule for other modules: reports, staff access to an event's participants,
 * recording that an event with participants took place, and the chats of classes and fixed groups.
 */
@Repository
public class ScheduledEvents {

	private static final RowMapper<AttendedEvent> ATTENDED_EVENT = (rs, row) -> new AttendedEvent(
			rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"), instant(rs, "starts_at"),
			instant(rs, "ends_at"));

	// Kinds of events that have participants who can talk in a chat.
	private static final List<String> CHAT_KINDS = List.of("CLASS", "WORKSHOP");

	private final JdbcClient jdbc;

	private final AuditLog auditLog;

	private final ServiceTypes serviceTypes;

	public ScheduledEvents(JdbcClient jdbc, AuditLog auditLog, ServiceTypes serviceTypes) {
		this.jdbc = jdbc;
		this.auditLog = auditLog;
		this.serviceTypes = serviceTypes;
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

	/**
	 * Classes and workshops among the given events, whatever their status; unknown ids and other
	 * kinds are left out. The title falls back to the service type's name.
	 */
	public List<EventSummary> findGroupEvents(Collection<UUID> eventIds) {
		if (eventIds.isEmpty()) {
			return List.of();
		}
		return summaries(jdbc.sql("""
				SELECT id, title, service_type_id, training_group_id, starts_at, ends_at, status
				FROM schedule_event
				WHERE id IN (:ids) AND kind IN (:kinds)
				""")
			.param("ids", eventIds)
			.param("kinds", CHAT_KINDS)
			.query(EventRow.class)
			.list());
	}

	/**
	 * Classes and workshops the staff member runs or assists that were not cancelled and end in
	 * {@code [from, to)}, earliest first.
	 */
	public List<EventSummary> findGroupEventsAssignedTo(UUID staffId, Instant from, Instant to) {
		return summaries(jdbc.sql("""
				SELECT e.id, e.title, e.service_type_id, e.training_group_id, e.starts_at, e.ends_at, e.status
				FROM schedule_event e
				JOIN event_staff s ON s.event_id = e.id
				WHERE s.staff_id = :staffId AND e.kind IN (:kinds) AND e.status <> 'CANCELLED'
				  AND e.ends_at >= :from AND e.ends_at < :to
				ORDER BY e.starts_at, e.id
				""")
			.param("staffId", staffId)
			.param("kinds", CHAT_KINDS)
			.param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
			.param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
			.query(EventRow.class)
			.list());
	}

	/** Classes and workshops the staff member runs or assists, whatever their status and time. */
	public Set<UUID> findGroupEventIdsAssignedTo(UUID staffId) {
		return jdbc.sql("""
				SELECT e.id
				FROM schedule_event e
				JOIN event_staff s ON s.event_id = e.id
				WHERE s.staff_id = :staffId AND e.kind IN (:kinds)
				""")
			.param("staffId", staffId)
			.param("kinds", CHAT_KINDS)
			.query(UUID.class)
			.set();
	}

	/** Staff members who run or assist the event. */
	public Set<UUID> findStaff(UUID eventId) {
		return jdbc.sql("SELECT staff_id FROM event_staff WHERE event_id = :eventId")
			.param("eventId", eventId)
			.query(UUID.class)
			.set();
	}

	/**
	 * Fixed groups the staff member runs: groups of classes they run or assist that were not
	 * cancelled and have not ended at {@code now}.
	 */
	public Set<UUID> findGroupsLedBy(UUID staffId, Instant now) {
		return jdbc.sql("""
				SELECT DISTINCT e.training_group_id
				FROM schedule_event e
				JOIN event_staff s ON s.event_id = e.id
				WHERE s.staff_id = :staffId AND e.training_group_id IS NOT NULL AND e.status <> 'CANCELLED'
				  AND e.ends_at > :now
				""")
			.param("staffId", staffId)
			.param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
			.query(UUID.class)
			.set();
	}

	/** Staff members who run or assist the group's classes that were not cancelled and have not ended. */
	public Set<UUID> findGroupLeaders(UUID groupId, Instant now) {
		return jdbc.sql("""
				SELECT DISTINCT s.staff_id
				FROM schedule_event e
				JOIN event_staff s ON s.event_id = e.id
				WHERE e.training_group_id = :groupId AND e.status <> 'CANCELLED' AND e.ends_at > :now
				""")
			.param("groupId", groupId)
			.param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
			.query(UUID.class)
			.set();
	}

	/** The group's classes that were not cancelled and have not ended at {@code now}. */
	public Set<UUID> findUpcomingEventIdsOfGroup(UUID groupId, Instant now) {
		return jdbc.sql("""
				SELECT id
				FROM schedule_event
				WHERE training_group_id = :groupId AND status <> 'CANCELLED' AND ends_at > :now
				""")
			.param("groupId", groupId)
			.param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
			.query(UUID.class)
			.set();
	}

	/** Fixed groups of the given events that were not cancelled and have not ended at {@code now}. */
	public Set<UUID> findGroupsOfUpcoming(Collection<UUID> eventIds, Instant now) {
		if (eventIds.isEmpty()) {
			return Set.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT training_group_id
				FROM schedule_event
				WHERE id IN (:ids) AND training_group_id IS NOT NULL AND status <> 'CANCELLED' AND ends_at > :now
				""")
			.param("ids", eventIds)
			.param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
			.query(UUID.class)
			.set();
	}

	private List<EventSummary> summaries(List<EventRow> rows) {
		Map<UUID, String> names = serviceTypes
			.findAllById(rows.stream().map(EventRow::serviceTypeId).filter((id) -> id != null).distinct().toList())
			.stream()
			.collect(Collectors.toMap(ServiceTypeSummary::id, ServiceTypeSummary::name));
		return rows.stream()
			.map((row) -> new EventSummary(row.id(),
					(row.title() != null && !row.title().isBlank()) ? row.title() : names.getOrDefault(row.serviceTypeId(), ""),
					row.trainingGroupId(), row.startsAt().toInstant(), row.endsAt().toInstant(),
					"CANCELLED".equals(row.status())))
			.toList();
	}

	private record EventRow(UUID id, String title, UUID serviceTypeId, UUID trainingGroupId, OffsetDateTime startsAt,
			OffsetDateTime endsAt, String status) {
	}

	/**
	 * A class or workshop as other modules see it.
	 *
	 * @param trainingGroupId the fixed group the class belongs to, or {@code null}
	 */
	public record EventSummary(UUID id, String title, UUID trainingGroupId, Instant startsAt, Instant endsAt,
			boolean cancelled) {
	}

}
