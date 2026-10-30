package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Events of the schedule for other modules' reports.
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

}
