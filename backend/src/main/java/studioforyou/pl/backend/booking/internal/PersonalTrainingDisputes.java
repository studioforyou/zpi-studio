package studioforyou.pl.backend.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
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
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.DisputeResponse;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.DisputeStatus;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.Option;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.Options;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.ResolveRequest;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeEffect;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeOption;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeResolution;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeTerms;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.PersonalTrainingSchedule;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * Administrators decide on the disputes clients report about held personal sessions (#113). The
 * decision settles the session for good: an upheld dispute gives the entry or make-up credit back,
 * or grants a new make-up credit, and a rejected one keeps the entry used. Lock order is event,
 * reservation and dispute, then the pass or credit, as when the client reports the dispute.
 */
@Service
@Transactional
class PersonalTrainingDisputes {

	private static final String SELECT = """
			SELECT d.id, d.status, d.reason, d.raised_at, d.raised_by, d.resolution, d.resolution_effect,
			       d.resolution_note, d.resolved_by, d.resolved_at, r.id AS reservation_id, r.event_id, r.customer_id,
			       r.customer_pass_id, r.makeup_credit_id, r.attendance, r.trainer_confirmed_at, r.version
			FROM training_dispute d JOIN reservation r ON r.id = d.reservation_id
			""";

	private final JdbcClient jdbc;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	private final PersonalTrainingSchedule schedule;

	private final ScheduledEvents events;

	private final PersonalTrainingAccounting accounting;

	private final AuditLog audit;

	private final Clock clock = Clock.systemUTC();

	PersonalTrainingDisputes(JdbcClient jdbc, CustomerProfiles customers, StaffProfiles staff,
			PersonalTrainingSchedule schedule, ScheduledEvents events, PersonalTrainingAccounting accounting,
			AuditLog audit) {
		this.jdbc = jdbc;
		this.customers = customers;
		this.staff = staff;
		this.schedule = schedule;
		this.events = events;
		this.accounting = accounting;
		this.audit = audit;
	}

	/** Open disputes oldest first, so the longest waiting come first; resolved ones latest decision first. */
	@Transactional(readOnly = true)
	PageResponse<DisputeResponse> list(DisputeStatus status, int page, int size) {
		long total = jdbc.sql("SELECT count(*) FROM training_dispute WHERE status = :status")
			.param("status", status.name()).query(Long.class).single();
		String order = status == DisputeStatus.OPEN ? " ORDER BY d.raised_at, d.id" : " ORDER BY d.resolved_at DESC, d.id DESC";
		List<Row> rows = jdbc.sql(SELECT + " WHERE d.status = :status" + order + " LIMIT :size OFFSET :offset")
			.param("status", status.name()).param("size", size).param("offset", (long) page * size)
			.query((rs, n) -> map(rs)).list();
		return PageResponse.of(responses(rows, false), page, size, total);
	}

	@Transactional(readOnly = true)
	DisputeResponse get(UUID disputeId) {
		return responses(List.of(row(disputeId, false)), true).stream().findFirst()
			.orElseThrow(PersonalTrainingDisputes::notFound);
	}

	DisputeResponse resolve(UUID actorId, UUID disputeId, ResolveRequest request) {
		Row reference = row(disputeId, false);
		events.lockAttended(reference.eventId()).orElseThrow(PersonalTrainingDisputes::notFound);
		Row row = row(disputeId, true);
		if (!DisputeStatus.OPEN.name().equals(row.status())) {
			throw new ApiException(ErrorCode.DISPUTE_ALREADY_RESOLVED, "The dispute has already been resolved.",
					row.resolution() == null ? Map.of() : Map.of("resolution", row.resolution()));
		}
		if (row.version() != request.version()) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT, "The session has changed. Refresh the dispute and try again.");
		}
		Instant now = clock.instant();
		String note = request.note().strip();
		DisputeEffect effect = accounting.settleDispute(row.customerId(), row.passId(), row.creditId(),
				row.reservationId(), request.resolution(), request.makeupValidityDays(), note, actorId, now);
		jdbc.sql("""
				UPDATE training_dispute SET status = 'RESOLVED', resolution = :resolution, resolution_effect = :effect,
				resolution_note = :note, resolved_by = :actor, resolved_at = :now WHERE id = :id
				""").param("resolution", request.resolution().name()).param("effect", effect.name()).param("note", note)
			.param("actor", actorId).param("now", utc(now)).param("id", disputeId).update();
		jdbc.sql("""
				UPDATE reservation SET settlement_status = 'FINAL', updated_at = :now, version = version + 1
				WHERE id = :id
				""").param("now", utc(now)).param("id", row.reservationId()).update();
		Map<String, AuditLog.Change> changes = new HashMap<>();
		changes.put("settlementStatus", new AuditLog.Change("DISPUTED", "FINAL"));
		changes.put("disputeResolution", new AuditLog.Change(null, request.resolution()));
		changes.put("disputeEffect", new AuditLog.Change(null, effect));
		if (effect == DisputeEffect.MAKEUP_GRANTED) {
			changes.put("makeupValidityDays", new AuditLog.Change(null, request.makeupValidityDays()));
		}
		audit.append(new AuditLog.Entry(actorId, "booking", "reservation", row.reservationId(),
				"PERSONAL_TRAINING_DISPUTE_RESOLVED", changes, note));
		return get(disputeId);
	}

	private List<DisputeResponse> responses(List<Row> rows, boolean single) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, Occurrence> sessions = schedule.findReserved(rows.stream().map(Row::eventId).distinct().toList(), null, null)
			.stream().collect(Collectors.toMap(Occurrence::id, Function.identity()));
		Map<UUID, ProfileDetails> profiles = customers.findAllById(rows.stream().map(Row::customerId).distinct().toList())
			.stream().collect(Collectors.toMap(ProfileDetails::id, Function.identity()));
		Map<UUID, String> names = names(rows.stream()
			.flatMap(row -> java.util.stream.Stream.of(row.raisedBy(), row.resolvedBy())).filter(Objects::nonNull)
			.distinct().toList());
		Instant now = clock.instant();
		return rows.stream().filter(row -> sessions.containsKey(row.eventId()) && profiles.containsKey(row.customerId()))
			.map(row -> {
				Occurrence session = sessions.get(row.eventId());
				ProfileDetails profile = profiles.get(row.customerId());
				boolean open = DisputeStatus.OPEN.name().equals(row.status());
				return new DisputeResponse(row.id(), row.status(), row.reason(), row.raisedAt(),
						names.get(row.raisedBy()), row.reservationId(), row.version(), profile.id(), profile.kind(),
						profile.displayName(), single ? customers.findContactAccount(profile.id()).orElse(null) : null,
						session.id(), session.title(), session.serviceTypeName(), session.startsAt(), session.endsAt(),
						session.status(), session.staff(), row.attendance(), row.trainerConfirmedAt(),
						row.resolution() == null ? null : DisputeResolution.valueOf(row.resolution()),
						row.effect() == null ? null : DisputeEffect.valueOf(row.effect()), row.note(), row.resolvedAt(),
						row.resolvedBy() == null ? null : names.get(row.resolvedBy()),
						single && open ? options(row, now) : null);
			})
			.toList();
	}

	private Options options(Row row, Instant now) {
		DisputeTerms terms = accounting.disputeTerms(row.customerId(), row.passId(), row.creditId(), row.reservationId(), now);
		return new Options(terms.funding(), terms.passName(), terms.entriesRemaining(), terms.passValidUntil(),
				terms.makeupExpiresAt(), option(terms.upheld()), option(terms.upheldMakeup()),
				option(terms.option(DisputeResolution.REJECTED)), terms.makeupValidityDays());
	}

	private static Option option(DisputeOption option) {
		return new Option(option.allowed(), option.effect(), option.notice());
	}

	/** Staff names for administrators, otherwise the name of the account's own participant profile. */
	private Map<UUID, String> names(Collection<UUID> accountIds) {
		Map<UUID, String> names = new HashMap<>(customers.displayNamesByAccount(accountIds));
		accountIds.forEach(accountId -> staff.findByAccount(accountId)
			.ifPresent(member -> names.put(accountId, member.displayName())));
		return names;
	}

	private Row row(UUID disputeId, boolean lock) {
		return jdbc.sql(SELECT + " WHERE d.id = :id" + (lock ? " FOR UPDATE OF d, r" : ""))
			.param("id", disputeId).query((rs, n) -> map(rs)).optional()
			.orElseThrow(PersonalTrainingDisputes::notFound);
	}

	private static Row map(ResultSet rs) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("reason"),
				instant(rs, "raised_at"), rs.getObject("raised_by", UUID.class), rs.getString("resolution"),
				rs.getString("resolution_effect"), rs.getString("resolution_note"), rs.getObject("resolved_by", UUID.class),
				instant(rs, "resolved_at"), rs.getObject("reservation_id", UUID.class), rs.getObject("event_id", UUID.class),
				rs.getObject("customer_id", UUID.class), rs.getObject("customer_pass_id", UUID.class),
				rs.getObject("makeup_credit_id", UUID.class), rs.getString("attendance"),
				instant(rs, "trainer_confirmed_at"), rs.getInt("version"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime utc(Instant time) {
		return time.atOffset(ZoneOffset.UTC);
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Dispute not found.");
	}

	private record Row(UUID id, String status, String reason, Instant raisedAt, UUID raisedBy, String resolution,
			String effect, String note, UUID resolvedBy, Instant resolvedAt, UUID reservationId, UUID eventId,
			UUID customerId, UUID passId, UUID creditId, String attendance, Instant trainerConfirmedAt, int version) {
	}

}
