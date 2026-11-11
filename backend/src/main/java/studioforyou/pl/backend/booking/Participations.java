package studioforyou.pl.backend.booking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Who takes part in events, for other modules' reports, notifications and chats.
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

	/**
	 * Reservations that were not cancelled for any of the events, e.g. for reminders to their
	 * participants.
	 */
	public List<ActiveReservation> findActiveReservations(Collection<UUID> eventIds) {
		List<UUID> ids = List.copyOf(eventIds);
		List<ActiveReservation> reservations = new ArrayList<>();
		for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
			reservations.addAll(jdbc.sql("""
					SELECT id, event_id, customer_id, created_at
					FROM reservation
					WHERE event_id IN (:eventIds) AND status = 'ACTIVE'
					""")
				.param("eventIds", ids.subList(start, Math.min(start + BATCH_SIZE, ids.size())))
				.query((rs, rowNum) -> new ActiveReservation(rs.getObject("id", UUID.class),
						rs.getObject("event_id", UUID.class), rs.getObject("customer_id", UUID.class),
						rs.getObject("created_at", OffsetDateTime.class).toInstant()))
				.list());
		}
		return reservations;
	}

	/**
	 * @param customerId the participant's customer profile
	 * @param createdAt when the reservation was made
	 */
	public record ActiveReservation(UUID id, UUID eventId, UUID customerId, Instant createdAt) {
	}

	/** Events any of the participants has a reservation for that was not cancelled. */
	public Set<UUID> findActiveEventIds(Collection<UUID> profileIds) {
		if (profileIds.isEmpty()) {
			return Set.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT event_id
				FROM reservation
				WHERE customer_id IN (:ids) AND status = 'ACTIVE'
				""")
			.param("ids", List.copyOf(profileIds))
			.query(UUID.class)
			.set();
	}

}
