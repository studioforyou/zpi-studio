package studioforyou.pl.backend.booking.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.internal.PassHistoryDtos.Actor;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.ActorRole;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.MakeupCredit;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.PassHistoryCategory;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.PassHistoryEntry;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.PassHistoryResponse;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.PassSummary;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.Period;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.Session;
import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.membership.PassChangeHistory;
import studioforyou.pl.backend.membership.PassChangeHistory.Change;
import studioforyou.pl.backend.membership.PassChangeHistory.History;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * The administrators' history of one pass (#85): membership's changes of the pass, with the
 * reservations they concern described by booking and scheduling and the authors named. Each owner
 * reads only its own tables.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class PassHistoryQueries {

	private final PassChangeHistory passes;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	private final GroupClassSchedule schedule;

	private final JdbcClient jdbc;

	PassHistoryQueries(PassChangeHistory passes, CustomerProfiles customers, StaffProfiles staff,
			GroupClassSchedule schedule, JdbcClient jdbc) {
		this.passes = passes;
		this.customers = customers;
		this.staff = staff;
		this.schedule = schedule;
		this.jdbc = jdbc;
	}

	/**
	 * One page of the pass's changes, newest first.
	 *
	 * @param category only changes of this category, or {@code null} for all
	 */
	PassHistoryResponse history(UUID customerId, UUID passId, @Nullable PassHistoryCategory category, int page,
			int size) {
		History history = passes.find(customerId, passId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass not found."));
		Map<PassHistoryCategory, Long> counts = new EnumMap<>(PassHistoryCategory.class);
		for (PassHistoryCategory each : PassHistoryCategory.values()) {
			counts.put(each, 0L);
		}
		history.changes().forEach((change) -> counts.merge(PassHistoryCategory.of(change.kind()), 1L, Long::sum));

		List<Change> matching = history.changes()
			.reversed()
			.stream()
			.filter((change) -> category == null || PassHistoryCategory.of(change.kind()) == category)
			.toList();
		int from = (int) Math.min((long) page * size, matching.size());
		List<Change> onPage = matching.subList(from, Math.min(from + size, matching.size()));

		Context context = context(customerId, onPage);
		List<PassHistoryEntry> entries = onPage.stream().map((change) -> entry(change, context)).toList();
		PassSummary pass = new PassSummary(history.passId(), history.customerId(), history.productName(),
				history.entriesTotal(), history.entriesRemaining(), history.ledgerBalance(), history.purchased());
		return new PassHistoryResponse(pass, counts, PageResponse.of(entries, page, size, matching.size()));
	}

	private PassHistoryEntry entry(Change change, Context context) {
		PassChangeHistory.Period period = change.suspension();
		PassChangeHistory.MakeupCredit credit = change.makeupCredit();
		return new PassHistoryEntry(change.id(), change.kind(), PassHistoryCategory.of(change.kind()), change.occurredAt(),
				context.actor(change.actorAccountId()), change.reason(), change.entriesChange(), change.balanceAfter(),
				change.validUntilBefore(), change.validUntilAfter(),
				(period == null) ? null : new Period(period.startsOn(), period.endsOn()),
				context.session(change.reservationId()),
				(credit == null) ? null : new MakeupCredit(credit.id(), credit.expiresAt(), credit.state(),
						credit.usedAt(), context.session(credit.usedReservationId())));
	}

	/** The reservations, events and authors the page's changes mention. */
	private Context context(UUID customerId, List<Change> changes) {
		Set<UUID> reservationIds = changes.stream()
			.flatMap((change) -> Stream.of(change.reservationId(),
					(change.makeupCredit() == null) ? null : change.makeupCredit().usedReservationId()))
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, ReservationRow> reservations = reservations(customerId, reservationIds);
		Map<UUID, Occurrence> events = schedule
			.findAllById(reservations.values().stream().map(ReservationRow::eventId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Occurrence::id, Function.identity()));

		Set<UUID> accountIds = new HashSet<>();
		changes.forEach((change) -> accountIds.add(change.actorAccountId()));
		reservations.values().forEach((reservation) -> {
			accountIds.add(reservation.bookedBy());
			accountIds.add(reservation.cancelledBy());
		});
		accountIds.remove(null);
		CustomerKind kind = customers.findById(customerId).map(CustomerProfiles.ProfileDetails::kind)
			.orElse(CustomerKind.ADULT);
		Set<UUID> actingFor = customers.findAccountsActingFor(List.of(customerId));
		return new Context(reservations, events, names(accountIds), actingFor, kind);
	}

	private Map<UUID, ReservationRow> reservations(UUID customerId, Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("""
				SELECT id, event_id, source, status, booked_by, cancelled_at, cancelled_by, cancel_kind, attendance
				FROM reservation WHERE id IN (:ids) AND customer_id = :customer
				""")
			.param("ids", ids)
			.param("customer", customerId)
			.query((rs, row) -> new ReservationRow(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
					rs.getString("source"), rs.getString("status"), rs.getObject("booked_by", UUID.class),
					instant(rs, "cancelled_at"), rs.getObject("cancelled_by", UUID.class), rs.getString("cancel_kind"),
					rs.getString("attendance")))
			.list()
			.stream()
			.collect(Collectors.toMap(ReservationRow::id, Function.identity()));
	}

	/** The authors' names; a staff member's name wins, as in the other staff views. */
	private Map<UUID, String> names(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> names = new HashMap<>(customers.displayNamesByAccount(accountIds));
		names.putAll(staff.displayNamesByAccount(accountIds));
		return names;
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value == null) ? null : value.toInstant();
	}

	private record ReservationRow(UUID id, UUID eventId, String source, String status, UUID bookedBy,
			@Nullable Instant cancelledAt, @Nullable UUID cancelledBy, @Nullable String cancelKind, String attendance) {
	}

	private record Context(Map<UUID, ReservationRow> reservations, Map<UUID, Occurrence> events,
			Map<UUID, String> names, Set<UUID> actingFor, CustomerKind kind) {

		@Nullable Actor actor(@Nullable UUID accountId) {
			if (accountId == null) {
				return null;
			}
			ActorRole role = !actingFor.contains(accountId) ? ActorRole.STAFF
					: (kind == CustomerKind.CHILD) ? ActorRole.GUARDIAN : ActorRole.CLIENT;
			return new Actor(names.get(accountId), role);
		}

		@Nullable Session session(@Nullable UUID reservationId) {
			ReservationRow reservation = (reservationId == null) ? null : reservations.get(reservationId);
			if (reservation == null) {
				return null;
			}
			Occurrence event = events.get(reservation.eventId());
			return new Session(reservation.id(), reservation.eventId(), (event == null) ? null : event.title(),
					(event == null) ? null : event.serviceTypeName(), (event == null) ? null : event.startsAt(),
					(event == null) ? null : event.endsAt(), event != null && "CANCELLED".equals(event.status()),
					reservation.source(), actor(reservation.bookedBy()), reservation.status(), reservation.cancelKind(),
					reservation.cancelledAt(), actor(reservation.cancelledBy()), reservation.attendance());
		}

	}

}
