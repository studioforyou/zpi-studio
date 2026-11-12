package studioforyou.pl.backend.booking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

	/**
	 * What became of the places booked for each of the events, for reports. Events without any
	 * reservation or waiting-list entry are left out.
	 */
	public Map<UUID, EventOutcomes> countOutcomes(Collection<UUID> eventIds) {
		List<UUID> ids = List.copyOf(eventIds);
		Map<UUID, EventOutcomes> outcomes = new HashMap<>();
		for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
			List<UUID> batch = ids.subList(start, Math.min(start + BATCH_SIZE, ids.size()));
			Map<UUID, Integer> waitlisted = new HashMap<>();
			jdbc.sql("""
					SELECT event_id, count(DISTINCT customer_id) AS waitlisted
					FROM waitlist_entry
					WHERE event_id IN (:eventIds)
					GROUP BY event_id
					""")
				.param("eventIds", batch)
				.query((rs, rowNum) -> Map.entry(rs.getObject("event_id", UUID.class), rs.getInt("waitlisted")))
				.list()
				.forEach((entry) -> waitlisted.put(entry.getKey(), entry.getValue()));
			jdbc.sql("""
					SELECT event_id,
					       count(*) FILTER (WHERE status = 'ACTIVE') AS booked,
					       count(*) FILTER (WHERE status = 'ACTIVE' AND attendance = 'PRESENT') AS present,
					       count(*) FILTER (WHERE status = 'ACTIVE' AND attendance = 'ABSENT') AS absent,
					       count(*) FILTER (WHERE status = 'CANCELLED' AND cancel_kind = 'IN_TIME') AS cancelled_in_time,
					       count(*) FILTER (WHERE status = 'CANCELLED' AND cancel_kind = 'LATE') AS cancelled_late,
					       count(*) FILTER (WHERE status = 'CANCELLED' AND cancel_kind = 'LATE'
					                        AND entry_returned = FALSE) AS late_entries_forfeited
					FROM reservation
					WHERE event_id IN (:eventIds)
					GROUP BY event_id
					""")
				.param("eventIds", batch)
				.query((rs, rowNum) -> {
					UUID eventId = rs.getObject("event_id", UUID.class);
					return Map.entry(eventId, new EventOutcomes(rs.getInt("booked"), rs.getInt("present"),
							rs.getInt("absent"), rs.getInt("cancelled_in_time"), rs.getInt("cancelled_late"),
							rs.getInt("late_entries_forfeited"), waitlisted.getOrDefault(eventId, 0)));
				})
				.list()
				.forEach((entry) -> outcomes.put(entry.getKey(), entry.getValue()));
			// A waiting list usually comes with reservations, but nothing guarantees it.
			waitlisted.forEach((eventId, count) -> outcomes.putIfAbsent(eventId,
					new EventOutcomes(0, 0, 0, 0, 0, 0, count)));
		}
		return outcomes;
	}

	/**
	 * The places of one event.
	 *
	 * @param booked places not cancelled, whatever their attendance
	 * @param present places marked present
	 * @param absent places marked absent: participants who did not come without cancelling
	 * @param cancelledInTime places the participant (or staff for them) cancelled before the free
	 * cancellation deadline
	 * @param cancelledLate places cancelled after the free cancellation deadline
	 * @param lateEntriesForfeited late cancellations whose pass entry was not returned
	 * @param waitlisted participants who joined the event's waiting list, whatever became of it
	 */
	public record EventOutcomes(int booked, int present, int absent, int cancelledInTime, int cancelledLate,
			int lateEntriesForfeited, int waitlisted) {

		public static final EventOutcomes NONE = new EventOutcomes(0, 0, 0, 0, 0, 0, 0);

		/** Places nobody has marked present or absent yet. */
		public int unmarked() {
			return this.booked - this.present - this.absent;
		}

	}

	/**
	 * Places cancelled by the participant or by staff on their behalf, in time or late, for any of
	 * the events, e.g. to see how long before the start people cancel.
	 */
	public List<Cancellation> findCancellations(Collection<UUID> eventIds) {
		List<UUID> ids = List.copyOf(eventIds);
		List<Cancellation> cancellations = new ArrayList<>();
		for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
			cancellations.addAll(jdbc.sql("""
					SELECT event_id, cancel_kind, cancelled_at
					FROM reservation
					WHERE event_id IN (:eventIds) AND status = 'CANCELLED' AND cancel_kind IN ('IN_TIME', 'LATE')
					  AND cancelled_at IS NOT NULL
					""")
				.param("eventIds", ids.subList(start, Math.min(start + BATCH_SIZE, ids.size())))
				.query((rs, rowNum) -> new Cancellation(rs.getObject("event_id", UUID.class),
						"LATE".equals(rs.getString("cancel_kind")),
						rs.getObject("cancelled_at", OffsetDateTime.class).toInstant()))
				.list());
		}
		return cancellations;
	}

	/** @param late whether it came after the free cancellation deadline */
	public record Cancellation(UUID eventId, boolean late, Instant cancelledAt) {
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

	/** Rechecks an allocation before delayed notification processing or a push retry. */
	public boolean isCurrentWaitlistAllocation(WaitlistPlaceAllocated allocation, Instant now) {
		if (allocation.offerExpiresAt() != null) {
			return jdbc.sql("""
					SELECT EXISTS (SELECT 1 FROM waitlist_entry
					 WHERE id = :id AND customer_id = :customer AND event_id = :event
					   AND status = 'OFFERED' AND offer_expires_at = :expires AND offer_expires_at > :now)
					""").param("id", allocation.entryId()).param("customer", allocation.customerId())
				.param("event", allocation.eventId()).param("expires", allocation.offerExpiresAt().atOffset(java.time.ZoneOffset.UTC))
				.param("now", now.atOffset(java.time.ZoneOffset.UTC)).query(Boolean.class).single();
		}
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM waitlist_entry w JOIN reservation r ON r.id = w.reservation_id
				 WHERE w.id = :id AND w.customer_id = :customer AND w.event_id = :event
				   AND w.status = 'PROMOTED' AND w.reservation_id = :reservation AND r.status = 'ACTIVE')
				""").param("id", allocation.entryId()).param("customer", allocation.customerId())
			.param("event", allocation.eventId()).param("reservation", allocation.reservationId())
			.query(Boolean.class).single();
	}

}
