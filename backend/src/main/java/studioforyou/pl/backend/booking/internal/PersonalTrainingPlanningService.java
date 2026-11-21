package studioforyou.pl.backend.booking.internal;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.booking.internal.PersonalTrainingPlanningController.CreateRequest;
import studioforyou.pl.backend.booking.internal.PersonalTrainingPlanningController.Created;
import studioforyou.pl.backend.booking.internal.PersonalTrainingPlanningController.Preview;
import studioforyou.pl.backend.booking.internal.PersonalTrainingPlanningController.PreviewRequest;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.OwnTrainees;
import studioforyou.pl.backend.customer.StaffAssignments;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.scheduling.PersonalTrainingPlanning;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/** One transaction: a private session, its participant, funding and the trainer relationship. */
@Service
@Transactional
class PersonalTrainingPlanningService {

	private final PersonalTrainingPlanning schedule;
	private final ReservationStore reservations;
	private final CustomerProfiles customers;
	private final OwnTrainees trainees;
	private final StaffAssignments assignments;
	private final StaffProfiles staff;
	private final BookingEntitlements entitlements;
	private final ParticipationRules rules;
	private final BookingPolicies policies;
	private final StudioSettings studio;
	private final JdbcClient jdbc;
	private final AuditLog audit;

	PersonalTrainingPlanningService(PersonalTrainingPlanning schedule, ReservationStore reservations,
			CustomerProfiles customers, OwnTrainees trainees, StaffAssignments assignments, StaffProfiles staff,
			BookingEntitlements entitlements, ParticipationRules rules, BookingPolicies policies, StudioSettings studio,
			JdbcClient jdbc, AuditLog audit) {
		this.schedule = schedule;
		this.reservations = reservations;
		this.customers = customers;
		this.trainees = trainees;
		this.assignments = assignments;
		this.staff = staff;
		this.entitlements = entitlements;
		this.rules = rules;
		this.policies = policies;
		this.studio = studio;
		this.jdbc = jdbc;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	Preview preview(UUID actorId, PreviewRequest request) {
		UUID staffId = staffId(actorId);
		var issues = schedule.preview(request.plan(), staffId);
		return check(request, staffId, issues);
	}

	Created create(UUID actorId, CreateRequest request) {
		UUID staffId = staffId(actorId);
		PreviewRequest plan = request.plan();
		// Different trainers adding the same participant serialize before checking their bookings.
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
			.param("key", "booking:personal-customer:" + plan.customerId()).query(rs -> {});
		var participant = customers.findById(plan.customerId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
		// Scheduling rechecks trainer/room conflicts under its own locks; a later failure rolls this back.
		UUID eventId = schedule.create(plan.plan(), staffId, actorId, participant.firstName() + " " + participant.lastName());
		Preview checked = check(plan, staffId, List.of());
		if (checked.participantReason() != null) throw ParticipationRules.rejected(checked.participantReason());
		if (checked.eligiblePasses().stream().noneMatch(p -> p.id().equals(request.customerPassId()))) {
			throw new ApiException(ErrorCode.PASS_NOT_USABLE, "Choose a pass eligible on the session date.");
		}
		var policy = policies.forOccurrence(plan.serviceTypeId(), null, eventId);
		var terms = policy.effective();
		UUID reservationId = UUID.randomUUID();
		reservations.insert(reservationId, eventId, plan.customerId(), actorId, "STAFF", policy.policyId(),
				plan.startsAt().minus(Duration.ofHours(terms.freeCancelHours())), terms.cancellationAllowed(),
				terms.lateCancelAllowed(), terms.lateCancelForfeitsEntry());
		entitlements.reserve(plan.customerId(), request.customerPassId(), plan.serviceTypeId(),
				LocalDate.ofInstant(plan.startsAt(), studio.zone()), reservationId, actorId);
		reservations.attachPass(reservationId, request.customerPassId());
		if (checked.startsCooperation()) trainees.assignToTrainer(plan.customerId(), actorId);
		audit.append(new AuditLog.Entry(actorId, "booking", "reservation", reservationId, "PERSONAL_TRAINING_PLANNED",
				Map.of("eventId", new AuditLog.Change(null, eventId),
						"customerPassId", new AuditLog.Change(null, request.customerPassId())), null));
		return new Created(eventId, reservationId);
	}

	private Preview check(PreviewRequest request, UUID staffId, List<PersonalTrainingPlanning.Issue> issues) {
		var participant = customers.findById(request.customerId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
		LocalDate date = LocalDate.ofInstant(request.startsAt(), studio.zone());
		var passes = entitlements.eligible(request.customerId(), request.serviceTypeId(), date);
		String reason = !participant.active() ? "CUSTOMER_NOT_ACTIVE"
				: schedule.overlaps(reservations.activeEventIds(request.customerId()), request.startsAt(), request.endsAt())
						? "CUSTOMER_SCHEDULE_CONFLICT" : null;
		if (reason == null) reason = rules.participantReason(participant, request.serviceTypeId(), date, !passes.isEmpty());
		return new Preview(issues, reason, passes, !assignments.isAssigned(request.customerId(), staffId));
	}

	private UUID staffId(UUID actorId) {
		return staff.getByAccount(actorId).id();
	}
}
