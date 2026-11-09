package studioforyou.pl.backend.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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

	private static final RowMapper<AttendanceRow> ATTENDANCE = (rs, row) -> new AttendanceRow(
			rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("status"),
			rs.getObject("customer_pass_id", UUID.class), rs.getString("attendance"),
			rs.getObject("attendance_marked_by", UUID.class), instant(rs, "attendance_marked_at"),
			instant(rs, "created_at"), rs.getInt("version"));

	private static final RowMapper<RosterRow> ROSTER = (rs, row) -> new RosterRow(MAPPER.mapRow(rs, row),
			rs.getObject("makeup_credit_id", UUID.class), rs.getString("source"), rs.getObject("booked_by", UUID.class),
			instant(rs, "created_at"));

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

	/** Locks one reservation of the event, whatever its status, after the caller has locked the event. */
	Optional<Reservation> lockInEvent(UUID eventId, UUID reservationId) {
		return this.jdbc.sql("SELECT * FROM reservation WHERE id = :id AND event_id = :event FOR UPDATE")
			.param("id", reservationId).param("event", eventId).query(MAPPER).optional();
	}

	/** Active reservations of the event with who booked them, earliest booking first. */
	List<RosterRow> roster(UUID eventId) {
		return this.jdbc.sql("""
				SELECT * FROM reservation
				WHERE event_id = :event AND status = 'ACTIVE' ORDER BY created_at, id
				""").param("event", eventId).query(ROSTER).list();
	}

	/** One reservation of the event with who booked it, whatever its status. */
	Optional<RosterRow> rosterRow(UUID eventId, UUID reservationId) {
		return this.jdbc.sql("SELECT * FROM reservation WHERE id = :id AND event_id = :event")
			.param("id", reservationId).param("event", eventId).query(ROSTER).optional();
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

	/** Active reservations of the event, earliest booking first. */
	List<ActiveReservation> activeReservations(UUID eventId) {
		return this.jdbc.sql("""
				SELECT id, customer_id, created_at FROM reservation
				WHERE event_id = :event AND status = 'ACTIVE' ORDER BY created_at, id
				""").param("event", eventId)
			.query((rs, row) -> new ActiveReservation(rs.getObject("id", UUID.class),
					rs.getObject("customer_id", UUID.class), instant(rs, "created_at")))
			.list();
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

	/** One reservation per event the customer booked: the active one, otherwise the latest cancelled. */
	List<PastReservation> history(UUID customerId) {
		return this.jdbc.sql("""
				SELECT DISTINCT ON (event_id) id, event_id, status, cancel_kind, entry_returned, attendance, personal_cancellation_effect
				FROM reservation WHERE customer_id = :customer
				ORDER BY event_id, status = 'ACTIVE' DESC, created_at DESC, id
				""").param("customer", customerId)
			.query((rs, row) -> new PastReservation(rs.getObject("id", UUID.class),
					rs.getObject("event_id", UUID.class), rs.getString("status"), rs.getString("cancel_kind"),
					rs.getObject("entry_returned", Boolean.class), rs.getString("attendance"), rs.getString("personal_cancellation_effect")))
			.list();
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

	/** @param entryReturned whether the pass entry was returned; {@code null} for a reservation without a pass */
	Reservation cancel(Reservation reservation, UUID actorId, Instant now, Boolean entryReturned, String kind) {
		return this.jdbc.sql("""
				UPDATE reservation SET status = 'CANCELLED', cancelled_at = :now, cancelled_by = :actor,
				cancel_kind = :kind, entry_returned = :returned, updated_at = :now, version = version + 1
				WHERE id = :id RETURNING *
				""").param("now", utc(now)).param("actor", actorId).param("kind", kind)
			.param("returned", entryReturned, Types.BOOLEAN).param("id", reservation.id()).query(MAPPER).single();
	}

	/** Active reservations of the event with their attendance, earliest booking first. */
	List<AttendanceRow> attendance(UUID eventId) {
		return this.jdbc.sql("""
				SELECT * FROM reservation
				WHERE event_id = :event AND status = 'ACTIVE' ORDER BY created_at, id
				""").param("event", eventId).query(ATTENDANCE).list();
	}

	/** Locks the event's active reservations in id order, after the caller has locked the event. */
	List<AttendanceRow> lockAttendance(UUID eventId) {
		return this.jdbc.sql("""
				SELECT * FROM reservation
				WHERE event_id = :event AND status = 'ACTIVE' ORDER BY id FOR UPDATE
				""").param("event", eventId).query(ATTENDANCE).list();
	}

	/** Locks one reservation of the event, whatever its status, after the caller has locked the event. */
	Optional<AttendanceRow> lockAttendance(UUID eventId, UUID reservationId) {
		return this.jdbc.sql("SELECT * FROM reservation WHERE id = :id AND event_id = :event FOR UPDATE")
			.param("id", reservationId).param("event", eventId).query(ATTENDANCE).optional();
	}

	/** Reservations of the event among the given ones that are no longer active. */
	List<UUID> inactiveAmong(UUID eventId, Collection<UUID> reservationIds) {
		return this.jdbc.sql("""
				SELECT id FROM reservation
				WHERE event_id = :event AND id IN (:ids) AND status <> 'ACTIVE'
				""").param("event", eventId).param("ids", List.copyOf(reservationIds)).query(UUID.class).list();
	}

	AttendanceRow markAttendance(UUID reservationId, String attendance, UUID actorId, Instant now) {
		return this.jdbc.sql("""
				UPDATE reservation SET attendance = :attendance, attendance_marked_by = :actor,
				attendance_marked_at = :now, updated_at = :now, version = version + 1
				WHERE id = :id RETURNING *
				""").param("attendance", attendance).param("actor", actorId).param("now", utc(now))
			.param("id", reservationId).query(ATTENDANCE).single();
	}

	/** Records the trainer's confirmation that a personal session took place, keeping an earlier one. */
	void confirmByTrainer(UUID eventId, Instant now) {
		this.jdbc.sql("""
				UPDATE reservation SET trainer_confirmed_at = :now, updated_at = :now, version = version + 1
				WHERE event_id = :event AND status = 'ACTIVE' AND trainer_confirmed_at IS NULL
				""").param("now", utc(now)).param("event", eventId).update();
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

	record PastReservation(UUID id, UUID eventId, String status, String cancelKind, Boolean entryReturned,
			String attendance, String cancellationEffect) {
	}

	record ActiveReservation(UUID id, UUID customerId, Instant bookedAt) {
	}

	/**
	 * A reservation with who booked it, as the studio staff manage it.
	 *
	 * @param source {@code SELF}, {@code GUARDIAN}, {@code STAFF}, {@code GROUP_AUTO} or {@code WAITLIST}
	 * @param bookedBy the account that booked the place
	 */
	record RosterRow(Reservation reservation, UUID makeupCreditId, String source, UUID bookedBy, Instant bookedAt) {
	}

	/**
	 * A reservation's attendance.
	 *
	 * @param attendance {@code UNMARKED}, {@code PRESENT} or {@code ABSENT}
	 * @param attendanceMarkedBy the account that recorded the current attendance, or {@code null}
	 */
	record AttendanceRow(UUID id, UUID customerId, String status, UUID customerPassId, String attendance,
			UUID attendanceMarkedBy, Instant attendanceMarkedAt, Instant bookedAt, int version) {
	}

}
