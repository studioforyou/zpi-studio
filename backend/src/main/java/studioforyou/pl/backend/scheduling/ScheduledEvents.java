package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Events of the schedule for other modules: reports and staff access to an event's participants.
 */
@Repository
public class ScheduledEvents {

	private final JdbcClient jdbc;

	public ScheduledEvents(JdbcClient jdbc) {
		this.jdbc = jdbc;
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

}
