package studioforyou.pl.backend.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.CancelRequest;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.Cancellation;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.Dispute;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.DisputeAvailability;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.DisputeRequest;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.Session;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.Effect;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.Quote;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.PersonalTrainingSchedule;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/** Event/reservation locks precede membership accounting; no other module's tables are queried. */
@Service
@Transactional
class PersonalTrainingService {
	private final JdbcClient jdbc;
	private final CustomerProfiles customers;
	private final PersonalTrainingSchedule schedule;
	private final ScheduledEvents events;
	private final BookingPolicies policies;
	private final StudioSettings studio;
	private final PersonalTrainingAccounting accounting;
	private final AuditLog audit;
	private final Clock clock = Clock.systemUTC();

	PersonalTrainingService(JdbcClient jdbc, CustomerProfiles customers, PersonalTrainingSchedule schedule,
			ScheduledEvents events, BookingPolicies policies, StudioSettings studio,
			PersonalTrainingAccounting accounting, AuditLog audit) {
		this.jdbc = jdbc;
		this.customers = customers;
		this.schedule = schedule;
		this.events = events;
		this.policies = policies;
		this.studio = studio;
		this.accounting = accounting;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	List<Session> list(UUID actorId, UUID profileId, Instant from, Instant to) {
		authorize(actorId, profileId);
		requireWindow(from, to);
		List<Row> reservations = rows(profileId);
		Map<UUID, Row> byEvent = reservations.stream().collect(Collectors.toMap(Row::eventId, Function.identity()));
		Instant now = clock.instant();
		return schedule.findReserved(byEvent.keySet(), from, to).stream()
			.map(event -> project(byEvent.get(event.id()), event, now)).toList();
	}

	@Transactional(readOnly = true)
	Session get(UUID actorId, UUID profileId, UUID reservationId) {
		authorize(actorId, profileId);
		Row row = row(profileId, reservationId, false);
		return project(row, event(row.eventId()), clock.instant());
	}

	Session cancel(UUID actorId, UUID profileId, UUID reservationId, CancelRequest request) {
		authorize(actorId, profileId);
		Row row = lock(profileId, reservationId);
		Occurrence event = event(row.eventId());
		Instant now = clock.instant();
		if ("CANCELLED".equals(row.status())) return project(row, event, now);
		requireVersion(row, request.expectedVersion());
		Cancellation cancellation = cancellation(row, event, now);
		if (!cancellation.allowed()) throw unavailable(cancellation.reason());
		if (request.expectedEffect() != cancellation.effect()
				|| !Objects.equals(request.expectedValidityDays(), cancellation.validityDays())) {
			throw new ApiException(ErrorCode.CANCELLATION_CONSEQUENCE_CHANGED, "Confirm the updated cancellation consequence.");
		}
		Quote quote = new Quote(cancellation.effect(), cancellation.validityDays(), cancellation.consequenceReason(), null);
		accounting.cancel(profileId, row.passId(), row.creditId(), row.id(), retainsEntitlement(row, event, now),
				quote, actorId, now);
		jdbc.sql("""
				UPDATE reservation SET status = 'CANCELLED', cancelled_at = :now, cancelled_by = :actor,
				cancel_kind = :kind, entry_returned = :returned, personal_cancellation_effect = :effect,
				settlement_status = 'FINAL', updated_at = :now, version = version + 1 WHERE id = :id
				""").param("id", row.id()).param("actor", actorId).param("now", utc(now))
			.param("kind", cancellation.late() ? "LATE" : "IN_TIME")
			.param("returned", quote.effect() == Effect.NO_ENTRY ? null : quote.effect() == Effect.MAKEUP_RETURNED, java.sql.Types.BOOLEAN)
			.param("effect", quote.effect().name()).update();
		if (jdbc.sql("SELECT count(*) FROM reservation WHERE event_id = :id AND status = 'ACTIVE'")
				.param("id", row.eventId()).query(Long.class).single() == 0) {
			events.cancelPersonal(row.eventId(), actorId);
		}
		audit.append(new AuditLog.Entry(actorId, "booking", "reservation", row.id(), "PERSONAL_TRAINING_CANCELLED",
				Map.of("status", new AuditLog.Change("ACTIVE", "CANCELLED"),
						"cancellationEffect", new AuditLog.Change(null, quote.effect())), null));
		return project(row(profileId, reservationId, false), event(row.eventId()), now);
	}

	Session raiseDispute(UUID actorId, UUID profileId, UUID reservationId, DisputeRequest request) {
		authorize(actorId, profileId);
		Row row = lock(profileId, reservationId);
		Occurrence event = event(row.eventId());
		Instant now = clock.instant();
		Dispute existing = dispute(row.id());
		if (existing != null && "OPEN".equals(existing.status())) return project(row, event, now);
		requireVersion(row, request.expectedVersion());
		DisputeAvailability available = disputeAvailability(row, event, existing, now);
		if (!available.allowed()) throw unavailable(available.reason());
		jdbc.sql("INSERT INTO training_dispute (reservation_id, raised_by, reason, raised_at) VALUES (:id, :actor, :reason, :now)")
			.param("id", row.id()).param("actor", actorId).param("reason", request.reason().strip())
			.param("now", utc(now)).update();
		jdbc.sql("UPDATE reservation SET settlement_status = 'DISPUTED', version = version + 1, updated_at = :now WHERE id = :id")
			.param("id", row.id()).param("now", utc(now)).update();
		audit.append(new AuditLog.Entry(actorId, "booking", "reservation", row.id(), "PERSONAL_TRAINING_DISPUTED",
				Map.of("settlementStatus", new AuditLog.Change(row.settlement(), "DISPUTED")), request.reason().strip()));
		return project(row(profileId, reservationId, false), event, now);
	}

	private Session project(Row row, Occurrence event, Instant now) {
		Dispute dispute = dispute(row.id());
		Quote quote = quote(row, event, now);
		return new Session(event.id(), row.id(), "PERSONAL_TRAINING",
				"CANCELLED".equals(row.status()) ? "CANCELLED" : event.status(), event.title(), event.serviceTypeId(),
				event.serviceTypeName(), event.startsAt(), event.endsAt(), event.staff(), event.resources(),
				quote.passName(), row.attendance(), row.trainerConfirmedAt(),
				effectiveSettlement(row.settlement(), row.trainerConfirmedAt(), event.endsAt(),
						dispute != null && "OPEN".equals(dispute.status()), now, studio.personalTrainingDisputeHours()),
				row.cancellationEffect(),
				cancellation(row, event, now), disputeAvailability(row, event, dispute, now), dispute, row.version());
	}

	private Cancellation cancellation(Row row, Occurrence event, Instant now) {
		var terms = policies.forOccurrence(event.serviceTypeId(), event.trainingGroupId(), event.id()).effective();
		Instant deadline = deadline(row, event, terms);
		boolean late = now.isAfter(deadline);
		String reason = !"ACTIVE".equals(row.status()) ? "RESERVATION_NOT_ACTIVE"
				: !"SCHEDULED".equals(event.status()) ? "EVENT_NOT_BOOKABLE"
				: !event.startsAt().isAfter(now) ? "EVENT_ALREADY_STARTED"
				: !flag(row.cancelAllowed(), terms.cancellationAllowed()) ? "CANCELLATION_NOT_ALLOWED"
				: late && !flag(row.lateAllowed(), terms.lateCancelAllowed()) ? "FREE_CANCELLATION_EXPIRED" : null;
		Quote quote = reason == null ? quote(row, event, now) : null;
		return new Cancellation(reason == null, reason, deadline, late, quote == null ? null : quote.effect(),
				quote == null ? null : quote.validityDays(), quote == null ? null : quote.reason());
	}

	private Quote quote(Row row, Occurrence event, Instant now) {
		return accounting.preview(row.customerId(), row.passId(), row.creditId(), row.id(),
				retainsEntitlement(row, event, now), now);
	}

	private boolean retainsEntitlement(Row row, Occurrence event, Instant now) {
		var terms = policies.forOccurrence(event.serviceTypeId(), event.trainingGroupId(), event.id()).effective();
		return !now.isAfter(deadline(row, event, terms)) || !flag(row.lateForfeits(), terms.lateCancelForfeitsEntry());
	}

	private static Instant deadline(Row row, Occurrence event, BookingPolicies.Values terms) {
		return row.freeCancelUntil() == null ? event.startsAt().minus(Duration.ofHours(terms.freeCancelHours())) : row.freeCancelUntil();
	}

	private static boolean flag(Boolean snapshot, Boolean effective) {
		return snapshot == null ? Boolean.TRUE.equals(effective) : snapshot;
	}

	/** Time closes an undisputed window without needing a timer or changing any pass entry. */
	static String effectiveSettlement(String stored, Instant confirmedAt, Instant endsAt,
			boolean openDispute, Instant now, int windowHours) {
		if (openDispute) {
			return "DISPUTED";
		}
		if ("OPEN".equals(stored) && confirmedAt != null && !endsAt.isAfter(now)
				&& !now.isBefore(confirmedAt.plus(Duration.ofHours(windowHours)))) {
			return "FINAL";
		}
		return stored;
	}

	private DisputeAvailability disputeAvailability(Row row, Occurrence event, Dispute dispute, Instant now) {
		Instant closes = row.trainerConfirmedAt() == null ? null
				: row.trainerConfirmedAt().plus(Duration.ofHours(studio.personalTrainingDisputeHours()));
		String reason = dispute != null ? ("OPEN".equals(dispute.status()) ? "DISPUTE_ALREADY_REPORTED" : "DISPUTE_RESOLVED")
				: !"ACTIVE".equals(row.status()) || "CANCELLED".equals(event.status()) ? "PERSONAL_SESSION_NOT_HELD"
				: row.trainerConfirmedAt() == null || row.trainerConfirmedAt().isAfter(now) || event.endsAt().isAfter(now)
						? "PERSONAL_SESSION_NOT_CONFIRMED"
				: !now.isBefore(closes) ? "DISPUTE_WINDOW_CLOSED" : null;
		return new DisputeAvailability(reason == null, reason, closes);
	}

	private Dispute dispute(UUID reservationId) {
		return jdbc.sql("SELECT * FROM training_dispute WHERE reservation_id = :id ORDER BY raised_at DESC, id DESC LIMIT 1")
			.param("id", reservationId).query((rs, n) -> new Dispute(rs.getObject("id", UUID.class), rs.getString("status"),
					rs.getString("reason"), instant(rs, "raised_at"), rs.getString("resolution"),
					rs.getString("resolution_note"), instant(rs, "resolved_at"))).optional().orElse(null);
	}

	private List<Row> rows(UUID customerId) {
		return jdbc.sql("""
				SELECT DISTINCT ON (event_id) * FROM reservation WHERE customer_id = :id
				ORDER BY event_id, status = 'ACTIVE' DESC, created_at DESC, id
				""").param("id", customerId).query((rs, n) -> map(rs)).list();
	}

	private Row row(UUID customerId, UUID reservationId, boolean lock) {
		return jdbc.sql("SELECT * FROM reservation WHERE customer_id = :customer AND id = :id" + (lock ? " FOR UPDATE" : ""))
			.param("customer", customerId).param("id", reservationId).query((rs, n) -> map(rs))
			.optional().orElseThrow(PersonalTrainingService::notFound);
	}

	private Row lock(UUID customerId, UUID reservationId) {
		Row reference = row(customerId, reservationId, false);
		var event = events.lockAttended(reference.eventId()).orElseThrow(PersonalTrainingService::notFound);
		if (!"PERSONAL_TRAINING".equals(event.kind())) throw notFound();
		return row(customerId, reservationId, true);
	}

	private Occurrence event(UUID eventId) {
		return schedule.findReserved(List.of(eventId), null, null).stream().findFirst().orElseThrow(PersonalTrainingService::notFound);
	}

	private void authorize(UUID actorId, UUID profileId) {
		if (!customers.isActingFor(actorId, profileId)) throw notFound();
	}

	private static void requireVersion(Row row, int version) {
		if (row.version() != version) throw new ApiException(ErrorCode.VERSION_CONFLICT, "Refresh the session and try again.");
	}

	private static void requireWindow(Instant from, Instant to) {
		if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(93)) > 0) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE, "Maximum window: 93 days.");
		}
	}

	private static Row map(ResultSet rs) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class), rs.getObject("customer_id", UUID.class),
				rs.getString("status"), rs.getObject("customer_pass_id", UUID.class), rs.getObject("makeup_credit_id", UUID.class),
				instant(rs, "free_cancel_until"), rs.getObject("cancellation_allowed_snapshot", Boolean.class),
				rs.getObject("late_cancel_allowed_snapshot", Boolean.class), rs.getObject("late_cancel_forfeits_entry_snapshot", Boolean.class),
				instant(rs, "trainer_confirmed_at"), rs.getString("settlement_status"), rs.getString("personal_cancellation_effect"),
				rs.getString("attendance"), rs.getString("cancel_kind"), rs.getObject("entry_returned", Boolean.class), rs.getInt("version"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		var value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
	private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
	private static ApiException notFound() { return new ApiException(ErrorCode.NOT_FOUND, "Personal training not found."); }
	private static ApiException unavailable(String reason) { return new ApiException(ErrorCode.valueOf(reason), "Action unavailable: " + reason); }

	private record Row(UUID id, UUID eventId, UUID customerId, String status, UUID passId, UUID creditId,
			Instant freeCancelUntil, Boolean cancelAllowed, Boolean lateAllowed, Boolean lateForfeits, Instant trainerConfirmedAt,
			String settlement, String cancellationEffect, String attendance, String cancelKind, Boolean entryReturned, int version) {}
}
