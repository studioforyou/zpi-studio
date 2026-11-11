package studioforyou.pl.backend.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the waiting lists, a booking-owned table. The queue is ordered by
 * {@code created_at, id}; callers hold the occurrence lock for every change.
 */
@Repository
class WaitlistStore {

	static final String WAITING = "WAITING";

	static final String PROMOTED = "PROMOTED";

	static final String BOOKED = "BOOKED";

	static final String CANCELLED = "CANCELLED";

	static final String OFFERED = "OFFERED";

	static final String EXPIRED = "EXPIRED";

	private static final RowMapper<WaitlistEntry> MAPPER = (rs, row) -> new WaitlistEntry(
			rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
			rs.getObject("customer_id", UUID.class), rs.getObject("added_by", UUID.class), rs.getString("status"),
			rs.getObject("customer_pass_id", UUID.class), rs.getObject("reservation_id", UUID.class),
			instant(rs, "created_at"), instant(rs, "resolved_at"), instant(rs, "offer_expires_at"));

	private final JdbcClient jdbc;

	WaitlistStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<WaitlistEntry> find(UUID customerId, UUID entryId) {
		return this.jdbc.sql("SELECT * FROM waitlist_entry WHERE id = :id AND customer_id = :customer")
			.param("id", entryId).param("customer", customerId).query(MAPPER).optional();
	}

	Optional<WaitlistEntry> lock(UUID customerId, UUID entryId) {
		return this.jdbc.sql("SELECT * FROM waitlist_entry WHERE id = :id AND customer_id = :customer FOR UPDATE")
			.param("id", entryId).param("customer", customerId).query(MAPPER).optional();
	}

	/** The participant's open entry for the event: waiting, or holding an offered place. */
	Optional<WaitlistEntry> lockOpen(UUID customerId, UUID eventId) {
		return this.jdbc.sql("""
				SELECT * FROM waitlist_entry
				WHERE event_id = :event AND customer_id = :customer AND status IN ('WAITING', 'OFFERED') FOR UPDATE
				""").param("event", eventId).param("customer", customerId).query(MAPPER).optional();
	}

	/** Per event, the customers still waiting for a place in it or holding an offer of one. */
	Map<UUID, List<UUID>> waitingCustomerIdsByEvent(Collection<UUID> eventIds) {
		if (eventIds.isEmpty()) {
			return Map.of();
		}
		return this.jdbc.sql("""
				SELECT event_id, customer_id FROM waitlist_entry
				WHERE event_id IN (:events) AND status IN ('WAITING', 'OFFERED') ORDER BY event_id, customer_id
				""")
			.param("events", eventIds)
			.query((rs, row) -> Map.entry(rs.getObject("event_id", UUID.class), rs.getObject("customer_id", UUID.class)))
			.list()
			.stream()
			.collect(Collectors.groupingBy(Map.Entry::getKey,
					Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
	}

	/** Places held for participants who can still confirm an offer. */
	int heldOffers(UUID eventId, Instant now) {
		return heldOffers(List.of(eventId), now).getOrDefault(eventId, 0);
	}

	Map<UUID, Integer> heldOffers(Collection<UUID> eventIds, Instant now) {
		if (eventIds.isEmpty()) {
			return Map.of();
		}
		return this.jdbc.sql("""
				SELECT event_id, count(*) AS total FROM waitlist_entry
				WHERE event_id IN (:events) AND status = 'OFFERED' AND offer_expires_at > :now GROUP BY event_id
				""").param("events", eventIds).param("now", utc(now))
			.query((rs, row) -> Map.entry(rs.getObject("event_id", UUID.class), rs.getInt("total")))
			.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/** Closes the event's offers whose confirmation time has run out, freeing their places. */
	int expireOffers(UUID eventId, Instant now) {
		return this.jdbc.sql("""
				UPDATE waitlist_entry SET status = 'EXPIRED', resolved_at = :now
				WHERE event_id = :event AND status = 'OFFERED' AND offer_expires_at <= :now
				""").param("now", utc(now)).param("event", eventId).update();
	}

	/** Events with an offer whose confirmation time has run out. */
	List<UUID> eventsWithExpiredOffers(Instant now) {
		return this.jdbc.sql("""
				SELECT DISTINCT event_id FROM waitlist_entry WHERE status = 'OFFERED' AND offer_expires_at <= :now
				""").param("now", utc(now)).query(UUID.class).list();
	}

	/** Holds a freed place for the waiting participant until they confirm it or the time runs out. */
	WaitlistEntry offer(UUID entryId, Instant expiresAt) {
		return this.jdbc.sql("""
				UPDATE waitlist_entry SET status = 'OFFERED', offer_expires_at = :expires WHERE id = :id RETURNING *
				""").param("expires", utc(expiresAt)).param("id", entryId).query(MAPPER).single();
	}

	/**
	 * The event's queue in promotion order. Participants who already hold a place are left out, so
	 * a promotion can never collide with their active reservation.
	 */
	List<WaitlistEntry> lockQueue(UUID eventId) {
		return this.jdbc.sql("""
				SELECT * FROM waitlist_entry w
				WHERE w.event_id = :event AND w.status = 'WAITING'
				AND NOT EXISTS (SELECT 1 FROM reservation r
				                WHERE r.event_id = w.event_id AND r.customer_id = w.customer_id AND r.status = 'ACTIVE')
				ORDER BY w.created_at, w.id FOR UPDATE
				""").param("event", eventId).query(MAPPER).list();
	}

	/**
	 * 1-based place of a waiting entry in its event's queue, or null when a concurrent change has
	 * resolved it since it was read without a lock.
	 */
	Integer position(WaitlistEntry entry) {
		return this.jdbc.sql("""
				SELECT position FROM (
				  SELECT id, row_number() OVER (ORDER BY created_at, id) AS position
				  FROM waitlist_entry WHERE event_id = :event AND status = 'WAITING'
				) queue WHERE id = :id
				""").param("event", entry.eventId()).param("id", entry.id()).query(Integer.class).optional()
			.orElse(null);
	}

	/** Participants waiting for each of the events that has a queue. */
	Map<UUID, Integer> waitingCounts(Collection<UUID> eventIds) {
		if (eventIds.isEmpty()) {
			return Map.of();
		}
		return this.jdbc.sql("""
				SELECT event_id, count(*) AS total FROM waitlist_entry
				WHERE event_id IN (:events) AND status = 'WAITING' GROUP BY event_id
				""").param("events", eventIds)
			.query((rs, row) -> Map.entry(rs.getObject("event_id", UUID.class), rs.getInt("total")))
			.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/**
	 * The participant's open entries for the events: a waiting one with its place in the queue, or
	 * one holding an offered place (no position).
	 */
	Map<UUID, QueuedEntry> ownWaiting(UUID customerId, Collection<UUID> eventIds) {
		if (eventIds.isEmpty()) {
			return Map.of();
		}
		return this.jdbc.sql("""
				SELECT * FROM (
				  SELECT w.*, CASE WHEN status = 'WAITING'
				    THEN row_number() OVER (PARTITION BY event_id, status ORDER BY created_at, id)::int END AS position
				  FROM waitlist_entry w WHERE event_id IN (:events) AND status IN ('WAITING', 'OFFERED')
				) queue WHERE customer_id = :customer
				""").param("events", eventIds).param("customer", customerId)
			.query((rs, row) -> new QueuedEntry(MAPPER.mapRow(rs, row), rs.getObject("position", Integer.class)))
			.list().stream().collect(Collectors.toMap(queued -> queued.entry().eventId(), queued -> queued));
	}

	/** The join time is set by the caller after the occurrence lock, so it follows the lock order. */
	WaitlistEntry insert(UUID entryId, UUID eventId, UUID customerId, UUID addedBy, UUID passId, Instant joinedAt) {
		return this.jdbc.sql("""
				INSERT INTO waitlist_entry (id, event_id, customer_id, added_by, customer_pass_id, created_at)
				VALUES (:id, :event, :customer, :addedBy, :pass, :joinedAt) RETURNING *
				""").param("id", entryId).param("event", eventId).param("customer", customerId)
			.param("addedBy", addedBy).param("pass", passId).param("joinedAt", utc(joinedAt))
			.query(MAPPER).single();
	}

	WaitlistEntry cancel(UUID entryId, Instant now) {
		return this.jdbc.sql("""
				UPDATE waitlist_entry SET status = 'CANCELLED', resolved_at = :now WHERE id = :id RETURNING *
				""").param("now", utc(now)).param("id", entryId).query(MAPPER).single();
	}

	/** Records the reservation the participant got from the list: {@link #PROMOTED} or {@link #BOOKED}. */
	WaitlistEntry resolve(UUID entryId, String status, UUID reservationId, Instant now) {
		return this.jdbc.sql("""
				UPDATE waitlist_entry SET status = :status, reservation_id = :reservation, resolved_at = :now
				WHERE id = :id RETURNING *
				""").param("status", status).param("reservation", reservationId).param("now", utc(now))
			.param("id", entryId).query(MAPPER).single();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime utc(Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}

	/**
	 * @param addedBy the account that joined the list, and later the one the reservation is booked by
	 * @param customerPassId the pass chosen when joining, or {@code null}
	 */
	record WaitlistEntry(UUID id, UUID eventId, UUID customerId, UUID addedBy, String status, UUID customerPassId,
			UUID reservationId, Instant joinedAt, Instant resolvedAt, Instant offerExpiresAt) {
	}

	/** An open entry with its 1-based place in the queue, null while it holds an offered place. */
	record QueuedEntry(WaitlistEntry entry, Integer position) {
	}

}
