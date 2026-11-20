package studioforyou.pl.backend.booking.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.internal.AutoCancellationDtos.AutoCancellationResponse;
import studioforyou.pl.backend.booking.internal.AutoCancellationDtos.State;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Administrators see what the automatic cancellation rule will do with a group class and force the
 * class to run although too few participants booked it (#136). The rule itself is applied by
 * {@link DefaultClassAutoCancellation}, with the same moment of the check.
 */
@Service
class EventAutoCancellationService {

	private final Clock clock = Clock.systemUTC();

	private final GroupClassSchedule schedule;

	private final ParticipationRules rules;

	private final BookingPolicies policies;

	private final ReservationStore reservations;

	private final ScheduledEvents events;

	EventAutoCancellationService(GroupClassSchedule schedule, ParticipationRules rules, BookingPolicies policies,
			ReservationStore reservations, ScheduledEvents events) {
		this.schedule = schedule;
		this.rules = rules;
		this.policies = policies;
		this.reservations = reservations;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public AutoCancellationResponse status(UUID eventId) {
		Occurrence event = this.schedule.findIncludingPrivate(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Group class not found."));
		return status(event, this.clock.instant());
	}

	/**
	 * Forces the class to run or lets the rule decide again, under the occurrence lock the automatic
	 * cancellation also holds: a class it has just cancelled is no longer scheduled.
	 */
	@Transactional
	public AutoCancellationResponse changeForceRun(UUID actorId, UUID eventId, boolean forceRun) {
		Occurrence event = this.schedule.lockIncludingPrivate(eventId);
		Instant now = this.clock.instant();
		if (!"SCHEDULED".equals(event.status()) || !event.startsAt().isAfter(now)) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE,
					"Only a scheduled class that has not started can be forced to run.");
		}
		this.events.changeForceRun(eventId, forceRun, actorId);
		return status(event, now);
	}

	private AutoCancellationResponse status(Occurrence event, Instant now) {
		BookingPolicies.Values policy = this.rules.terms(event).rules();
		boolean ruleEnabled = Boolean.TRUE.equals(policy.autoCancelEnabled())
				&& policy.autoCancelMinParticipants() != null;
		boolean forceRun = this.events.isForcedToRun(event.id());
		boolean disabledForGroup = event.trainingGroupId() != null
				&& this.policies.isAutoCancelDisabledForGroup(event.trainingGroupId());
		Instant checkAt = ruleEnabled ? DefaultClassAutoCancellation.checkMoment(event.startsAt(), policy) : null;
		return new AutoCancellationResponse(event.id(), state(event, ruleEnabled, forceRun, checkAt, now), ruleEnabled,
				forceRun, event.trainingGroupId(), disabledForGroup, this.reservations.activeCount(event.id()),
				ruleEnabled ? policy.autoCancelMinParticipants() : null, checkAt);
	}

	/**
	 * The class is open to the rule until it starts and while its check is not over
	 * ({@link DefaultClassAutoCancellation#GRACE} after its moment).
	 */
	static State state(Occurrence event, boolean ruleEnabled, boolean forceRun, Instant checkAt, Instant now) {
		if (!"SCHEDULED".equals(event.status()) || !event.startsAt().isAfter(now)) {
			return State.CLOSED;
		}
		if (forceRun) {
			return State.FORCED;
		}
		if (!ruleEnabled) {
			return State.OFF;
		}
		return now.isBefore(checkAt.plus(DefaultClassAutoCancellation.GRACE)) ? State.PENDING : State.CLOSED;
	}

}
