package studioforyou.pl.backend.booking;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who takes part in events, for other modules' reports.
 */
@Repository
public class Participations {

	// Keeps each query well below PostgreSQL's limit of bind parameters.
	private static final int BATCH_SIZE = 1000;

	private final JdbcClient jdbc;

	public Participations(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Participants with a reservation that was not cancelled for any of the events. */
	public Set<UUID> findParticipants(Collection<UUID> eventIds) {
		List<UUID> ids = List.copyOf(eventIds);
		Set<UUID> participants = new HashSet<>();
		for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
			participants.addAll(jdbc.sql("""
					SELECT DISTINCT customer_id
					FROM reservation
					WHERE event_id IN (:eventIds) AND status = 'ACTIVE'
					""")
				.param("eventIds", ids.subList(start, Math.min(start + BATCH_SIZE, ids.size())))
				.query(UUID.class)
				.set());
		}
		return participants;
	}

}
