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
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence of booking-owned rows only; occurrence and pass locks belong to their owners. */
@Repository
class ReservationStore {

	private static final RowMapper<Reservation> MAPPER = (rs, row) -> new Reservation(
			rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
			rs.getObject("customer_id", UUID.class), rs.getString("status"),
			rs.getObject("customer_pass_id", UUID.class), instant(rs, "free_cancel_until"),
			instant(rs, "cancelled_at"), rs.getString("cancel_kind"),
			rs.getObject("entry_returned", Boolean.class),
			rs.getObject("cancellation_allowed_snapshot", Boolean.class),
			rs.getObject("late_cancel_allowed_snapshot", Boolean.class),
			rs.getObject("late_cancel_forfeits_entry_snapshot", Boolean.class), rs.getInt("version"));

	private final JdbcClient jdbc;

	ReservationStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Reservation> find(UUID customerId, UUID reservationId) {
		return this.jdbc.sql("SELECT * FROM reservation WHERE id = :id AND customer_id = :customer")
			.param("id", reservationId).param("customer", customerId).query(MAPPER).optional();
	}

	Optional<Reservation> lock(UUID customerId, UUID reservationId) {
		return this.jdbc.sql("SELECT * FROM reservation WHERE id = :id AND customer_id = :customer FOR UPDATE")
			.param("id", reservationId).param("customer", customerId).query(MAPPER).optional();
	}

	Optional<Reservation> lockActive(UUID customerId, UUID eventId) {
		return this.jdbc.sql("""
				SELECT * FROM reservation
				WHERE event_id = :event AND customer_id = :customer AND status = 'ACTIVE' FOR UPDATE
				""").param("event", eventId).param("customer", customerId).query(MAPPER).optional();
	}

	int activeCount(UUID eventId) {
		return this.jdbc.sql("SELECT count(*) FROM reservation WHERE event_id = :event AND status = 'ACTIVE'")
			.param("event", eventId).query(Integer.class).single();
	}

	List<UUID> activeParticipantIds(UUID eventId) {
		return this.jdbc.sql("SELECT customer_id FROM reservation WHERE event_id = :event AND status = 'ACTIVE' ORDER BY customer_id")
			.param("event", eventId).query(UUID.class).list();
	}

	/** Authorization and the active roster use one statement snapshot of booking-owned rows. */
	List<UUID> activeParticipantIdsForBookedProfile(UUID profileId, UUID eventId) {
		return this.jdbc.sql("""
				SELECT participant.customer_id FROM reservation participant
				WHERE participant.event_id = :event AND participant.status = 'ACTIVE'
				AND EXISTS (SELECT 1 FROM reservation own
				            WHERE own.event_id = :event AND own.customer_id = :profile AND own.status = 'ACTIVE')
				ORDER BY participant.created_at, participant.id
				""").param("event", eventId).param("profile", profileId).query(UUID.class).list();
	}

	Map<UUID, Integer> activeCounts(Collection<UUID> eventIds) {
		if (eventIds.isEmpty()) {
			return Map.of();
		}
		return this.jdbc.sql("""
				SELECT event_id, count(*) AS total FROM reservation
				WHERE event_id IN (:events) AND status = 'ACTIVE' GROUP BY event_id
				""").param("events", eventIds)
			.query((rs, row) -> Map.entry(rs.getObject("event_id", UUID.class), rs.getInt("total")))
			.list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	Map<UUID, Reservation> ownActive(UUID customerId, Collection<UUID> eventIds) {
		if (eventIds.isEmpty()) {
			return Map.of();
		}
		return this.jdbc.sql("""
				SELECT * FROM reservation
				WHERE customer_id = :customer AND event_id IN (:events) AND status = 'ACTIVE'
				""").param("customer", customerId).param("events", eventIds).query(MAPPER).list()
			.stream().collect(Collectors.toMap(Reservation::eventId, Function.identity()));
	}

	Reservation insert(UUID reservationId, UUID eventId, UUID customerId, UUID actorId, String source,
			UUID policyId, Instant freeCancelUntil, boolean cancellationAllowed,
			boolean lateAllowed, boolean lateForfeits) {
		return this.jdbc.sql("""
				INSERT INTO reservation (id, event_id, customer_id, booked_by, source,
				booking_policy_id, free_cancel_until, cancellation_allowed_snapshot,
				late_cancel_allowed_snapshot, late_cancel_forfeits_entry_snapshot)
				VALUES (:id, :event, :customer, :actor, :source, :policy, :freeUntil,
				:cancelAllowed, :lateAllowed, :lateForfeits) RETURNING *
				""").param("id", reservationId).param("event", eventId).param("customer", customerId)
			.param("actor", actorId).param("source", source).param("policy", policyId)
			.param("freeUntil", utc(freeCancelUntil)).param("cancelAllowed", cancellationAllowed)
			.param("lateAllowed", lateAllowed).param("lateForfeits", lateForfeits).query(MAPPER).single();
	}

	/** Set funding after its owner has validated and allocated it under the pass lock. */
	Reservation attachPass(UUID reservationId, UUID passId) {
		return this.jdbc.sql("UPDATE reservation SET customer_pass_id = :pass WHERE id = :id RETURNING *")
			.param("pass", passId).param("id", reservationId).query(MAPPER).single();
	}

	Reservation cancel(Reservation reservation, UUID actorId, Instant now, boolean entryReturned, String kind) {
		return this.jdbc.sql("""
				UPDATE reservation SET status = 'CANCELLED', cancelled_at = :now, cancelled_by = :actor,
				cancel_kind = :kind, entry_returned = :returned, updated_at = :now, version = version + 1
				WHERE id = :id RETURNING *
				""").param("now", utc(now)).param("actor", actorId).param("kind", kind)
			.param("returned", entryReturned).param("id", reservation.id()).query(MAPPER).single();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime utc(Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}

	record Reservation(UUID id, UUID eventId, UUID customerId, String status, UUID customerPassId,
			Instant freeCancelUntil, Instant cancelledAt, String cancelKind, Boolean entryReturned,
			Boolean cancellationAllowed, Boolean lateCancelAllowed, Boolean lateCancelForfeitsEntry, int version) {
	}

}
