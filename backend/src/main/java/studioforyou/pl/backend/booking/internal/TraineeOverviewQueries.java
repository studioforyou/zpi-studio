package studioforyou.pl.backend.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.TraineeAssignments;
import studioforyou.pl.backend.customer.TraineeAssignments.Trainee;
import studioforyou.pl.backend.membership.TraineePasses;
import studioforyou.pl.backend.membership.TraineePasses.Funding;
import studioforyou.pl.backend.membership.TraineePasses.FundingRequest;
import studioforyou.pl.backend.membership.TraineePasses.Pass;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Instructor;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Room;
import studioforyou.pl.backend.scheduling.PersonalTrainingSchedule;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/** One consistent, read-only snapshot of a trainer's participants and their personal sessions. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class TraineeOverviewQueries {

	private static final Comparator<Session> BY_TIME = Comparator.comparing(Session::startsAt).thenComparing(Session::id)
			.thenComparing(Session::customerId).thenComparing(Session::reservationId);

	private final StaffProfiles staff;

	private final TraineeAssignments assignments;

	private final ServiceTypes services;

	private final TraineePasses passes;

	private final PersonalTrainingSchedule schedule;

	private final StudioSettings studio;

	private final JdbcClient jdbc;

	TraineeOverviewQueries(StaffProfiles staff, TraineeAssignments assignments, ServiceTypes services,
			TraineePasses passes, PersonalTrainingSchedule schedule, StudioSettings studio, JdbcClient jdbc) {
		this.staff = staff;
		this.assignments = assignments;
		this.services = services;
		this.passes = passes;
		this.schedule = schedule;
		this.studio = studio;
		this.jdbc = jdbc;
	}

	OverviewList list(UUID accountId) {
		Context context = context(accountId);
		return new OverviewList(context.checkedOn(), context.checkedAt(), overviews(context, context.trainees()));
	}

	OverviewDetail detail(UUID accountId, UUID customerId) {
		Context context = context(accountId);
		Trainee trainee = requireTrainee(context, customerId);
		Overview item = overviews(context, List.of(trainee)).getFirst();
		return new OverviewDetail(context.checkedOn(), context.checkedAt(), item.customerId(), item.displayName(),
				item.isActive(), item.availability(), item.primaryPassId(), item.passes(), item.nextTraining(), item.attentionTraining());
	}

	List<Session> calendar(UUID accountId, UUID customerId, Instant from, Instant to) {
		Context context = context(accountId);
		requireTrainee(context, customerId);
		requireWindow(from, to);
		return sessions(context, List.of(customerId), from, to).getOrDefault(customerId, List.of());
	}

	CalendarList calendar(UUID accountId, Instant from, Instant to) {
		Context context = context(accountId);
		requireWindow(from, to);
		List<UUID> activeIds = context.trainees().stream().filter(Trainee::active).map(Trainee::id).toList();
		List<Session> items = sessions(context, activeIds, from, to).values().stream().flatMap(List::stream).sorted(BY_TIME).toList();
		return new CalendarList(context.checkedOn(), context.checkedAt(), items);
	}

	private static void requireWindow(Instant from, Instant to) {
		if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(93)) > 0) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE,
					"The calendar window must be positive and no longer than 93 days.");
		}
	}

	private Context context(UUID accountId) {
		Instant checkedAt = Instant.now();
		LocalDate checkedOn = studio.today();
		UUID staffId = staff.findByAccount(accountId).map(StaffProfiles.StaffSummary::id).orElseThrow(TraineeOverviewQueries::notFound);
		if (staff.findAllById(List.of(staffId)).stream().noneMatch(StaffProfiles.StaffMember::active)) {
			throw notFound();
		}
		Set<UUID> qualificationIds = staff.findQualifications(List.of(staffId)).getOrDefault(staffId, Set.of());
		Set<UUID> qualifiedServices = services.findAllById(qualificationIds).stream()
			.filter(service -> service.active() && "PERSONAL_TRAINING".equals(service.category()))
			.map(ServiceTypeSummary::id).collect(Collectors.toSet());
		return new Context(staffId, checkedOn, checkedAt, studio.zone(), qualifiedServices,
				assignments.findCurrent(staffId, checkedAt));
	}

	private static Trainee requireTrainee(Context context, UUID customerId) {
		return context.trainees().stream().filter(trainee -> trainee.id().equals(customerId) && trainee.active())
			.findFirst().orElseThrow(TraineeOverviewQueries::notFound);
	}

	private List<Overview> overviews(Context context, List<Trainee> trainees) {
		List<UUID> activeIds = trainees.stream().filter(Trainee::active).map(Trainee::id).toList();
		Map<UUID, List<Session>> sessions = sessions(context, activeIds, null, null);
		Map<UUID, Session> nextSessions = new HashMap<>();
		sessions.forEach((customerId, ownSessions) -> ownSessions.stream()
			.filter(session -> !"CANCELLED".equals(session.status()) && !session.startsAt().isBefore(context.checkedAt()))
			.min(BY_TIME).ifPresent(session -> nextSessions.put(customerId, session)));
		Set<LocalDate> referenceDays = nextSessions.values().stream()
			.map(session -> session.startsAt().atZone(context.zone()).toLocalDate()).collect(Collectors.toSet());
		Map<UUID, List<Pass>> held = passes.find(activeIds, context.serviceIds(), context.checkedOn(), referenceDays);
		return trainees.stream().map(trainee -> {
			List<Session> ownSessions = sessions.getOrDefault(trainee.id(), List.of());
			Session next = nextSessions.get(trainee.id());
			Session attention = ownSessions.stream().filter(Session::requiresAttention)
				.sorted(Comparator.comparing(Session::startsAt).reversed().thenComparing(Session::id)).findFirst().orElse(null);
			List<Pass> available = held.getOrDefault(trainee.id(), List.of()).stream().sorted(passOrder()).toList();
			Pass primary = primary(available, next, context.zone());
			String availability = !trainee.active() ? "INACTIVE" : context.serviceIds().isEmpty() ? "NO_QUALIFIED_SERVICES"
					: available.stream().anyMatch(Pass::eligible) ? "AVAILABLE" : "UNAVAILABLE";
			return new Overview(trainee.id(), trainee.displayName(), trainee.active(), availability,
					primary == null ? null : primary.id(), available.stream().map(PassView::from).toList(), next, attention);
		}).toList();
	}

	private static Pass primary(List<Pass> held, Session next, ZoneId zone) {
		if (next != null) {
			if (next.passId() != null && next.funding().startsWith("PASS_")) {
				Pass attached = held.stream().filter(pass -> pass.id().equals(next.passId())).findFirst().orElse(null);
				if (attached != null) {
					return attached;
				}
			}
			LocalDate day = next.startsAt().atZone(zone).toLocalDate();
			Pass matching = held.stream().filter(pass -> pass.serviceTypeIds().contains(next.serviceTypeId())
					&& pass.eligibleDays().contains(day)).findFirst().orElse(null);
			if (matching != null) {
				return matching;
			}
		}
		return held.stream().findFirst().orElse(null);
	}

	private static Comparator<Pass> passOrder() {
		return Comparator.comparingInt((Pass pass) -> pass.eligible() ? 0 : switch (pass.status()) {
			case ACTIVE -> 1;
			case SUSPENDED -> 2;
			case SCHEDULED -> 3;
			case EXPIRED -> 4;
			case PENDING_PAYMENT -> 5;
			case CANCELLED -> 6;
		}).thenComparing(Pass::validUntil, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Pass::id);
	}

	private Map<UUID, List<Session>> sessions(Context context, Collection<UUID> customerIds, Instant from, Instant to) {
		if (customerIds.isEmpty()) {
			return Map.of();
		}
		List<Reservation> reservations = jdbc.sql("""
				SELECT DISTINCT ON (r.customer_id, r.event_id)
				       r.id, r.event_id, r.customer_id, r.status, r.customer_pass_id, r.makeup_credit_id,
				       r.trainer_confirmed_at, r.client_confirmed_at, r.attendance, r.settlement_status,
				       EXISTS (SELECT 1 FROM training_dispute d WHERE d.reservation_id = r.id AND d.status = 'OPEN') AS disputed
				FROM reservation r WHERE r.customer_id IN (:ids)
				ORDER BY r.customer_id, r.event_id, r.status = 'ACTIVE' DESC, r.created_at DESC, r.id
				""")
			.param("ids", customerIds)
			.query((rs, row) -> new Reservation(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
					rs.getObject("customer_id", UUID.class), rs.getString("status"), rs.getObject("customer_pass_id", UUID.class),
					rs.getObject("makeup_credit_id", UUID.class), instant(rs.getObject("trainer_confirmed_at", OffsetDateTime.class)),
					instant(rs.getObject("client_confirmed_at", OffsetDateTime.class)), rs.getString("attendance"),
					rs.getString("settlement_status"), rs.getBoolean("disputed")))
			.list();
		Map<UUID, Occurrence> ownEvents = schedule.findOwn(reservations.stream().map(Reservation::eventId).distinct().toList(),
				context.staffId(), from, to).stream().collect(Collectors.toMap(Occurrence::id, Function.identity()));
		List<Reservation> visible = reservations.stream().filter(reservation -> ownEvents.containsKey(reservation.eventId())).toList();
		Map<UUID, Funding> funding = passes.funding(visible.stream().map(reservation -> new FundingRequest(reservation.id(),
				reservation.customerId(), reservation.passId(), reservation.makeupCreditId())).toList());
		Map<UUID, String> customerNames = context.trainees().stream().collect(Collectors.toMap(Trainee::id, Trainee::displayName));
		Map<UUID, List<Session>> result = new HashMap<>();
		for (Reservation reservation : visible) {
			Occurrence event = ownEvents.get(reservation.eventId());
			Funding accounting = funding.get(reservation.id());
			String status = "CANCELLED".equals(reservation.status()) ? "CANCELLED" : event.status();
			String settlement = PersonalTrainingService.effectiveSettlement(reservation.settlementStatus(),
					reservation.trainerConfirmedAt(), event.endsAt(), reservation.disputed(),
					context.checkedAt(), studio.personalTrainingDisputeHours());
			boolean requiresAttention = attention(reservation, event, status, settlement, accounting, context.checkedAt());
			Session session = new Session(event.id(), "PERSONAL_TRAINING", status, event.title(), event.serviceTypeId(),
					event.serviceTypeName(), event.startsAt(), event.endsAt(), event.capacity(), event.staff(), event.resources(),
					reservation.id(), reservation.customerId(), customerNames.get(reservation.customerId()),
					reservation.trainerConfirmedAt(), reservation.clientConfirmedAt(), reservation.attendance(),
					settlement, accounting.state(), accounting.passId(), accounting.passName(), requiresAttention);
			result.computeIfAbsent(reservation.customerId(), ignored -> new java.util.ArrayList<>()).add(session);
		}
		result.values().forEach(items -> items.sort(BY_TIME));
		return result;
	}

	private static boolean attention(Reservation reservation, Occurrence event, String status, String settlement,
			Funding funding, Instant checkedAt) {
		if ("INCONSISTENT".equals(funding.state()) || "DISPUTED".equals(settlement)) {
			return true;
		}
		if ("CANCELLED".equals(status) || event.endsAt().isAfter(checkedAt)) {
			return false;
		}
		return !"FINAL".equals(settlement) || reservation.trainerConfirmedAt() == null
				|| !Set.of("PASS_CONSUMED", "MAKEUP_USED", "NO_PASS").contains(funding.state());
	}

	private static Instant instant(OffsetDateTime timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Trainee overview not found.");
	}

	private record Context(UUID staffId, LocalDate checkedOn, Instant checkedAt, ZoneId zone,
			Set<UUID> serviceIds, List<Trainee> trainees) {
	}

	private record Reservation(UUID id, UUID eventId, UUID customerId, String status, UUID passId, UUID makeupCreditId,
			Instant trainerConfirmedAt, Instant clientConfirmedAt, String attendance, String settlementStatus, boolean disputed) {
	}

	record OverviewList(LocalDate checkedOn, Instant checkedAt, List<Overview> items) {
	}

	record CalendarList(LocalDate checkedOn, Instant checkedAt, List<Session> items) {
	}

	record Overview(UUID customerId, String displayName, boolean isActive, String availability, UUID primaryPassId,
			List<PassView> passes, Session nextTraining, Session attentionTraining) {
	}

	record OverviewDetail(LocalDate checkedOn, Instant checkedAt, UUID customerId, String displayName, boolean isActive,
			String availability, UUID primaryPassId, List<PassView> passes, Session nextTraining, Session attentionTraining) {
	}

	record PassView(UUID id, String productName, String status, Short entriesRemaining, LocalDate validFrom,
			LocalDate validUntil, List<UUID> serviceTypeIds, boolean eligible, boolean lowBalance) {

		static PassView from(Pass pass) {
			return new PassView(pass.id(), pass.productName(), pass.status().name(), pass.entriesRemaining(),
					pass.validFrom(), pass.validUntil(), pass.serviceTypeIds().stream().sorted().toList(), pass.eligible(), pass.lowBalance());
		}

	}

	record Session(UUID id, String kind, String status, String title, UUID serviceTypeId, String serviceTypeName,
			Instant startsAt, Instant endsAt, Integer capacity, List<Instructor> staff, List<Room> resources,
			UUID reservationId, UUID customerId, String customerName, Instant trainerConfirmedAt, Instant clientConfirmedAt, String attendance,
			String settlementStatus, String funding, UUID passId, String passName, boolean requiresAttention) {
	}

}
