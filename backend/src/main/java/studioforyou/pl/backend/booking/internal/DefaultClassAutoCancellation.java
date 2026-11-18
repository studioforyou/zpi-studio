package studioforyou.pl.backend.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.ClassAutoCancellation;
import studioforyou.pl.backend.booking.internal.ReservationStore.Reservation;
import studioforyou.pl.backend.booking.internal.WaitlistStore.WaitlistEntry;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.MakeupEntitlements;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.AutoCancelCandidate;
import studioforyou.pl.backend.shared.api.ApiException;

/**
 * Cancels group classes too few participants booked ({@link ClassAutoCancellation}). The check of
 * a class is due from its moment ({@link #checkMoment}) for {@link #GRACE}, so a job run that
 * misses the moment still makes it, but a class is not cancelled long after its participants
 * could count on it. Each class is settled in its own transaction under its occurrence lock, like
 * any other change of the class.
 */
@Component
class DefaultClassAutoCancellation implements ClassAutoCancellation {

	/** How long after the moment the check is still made; longer than the job interval. */
	static final Duration GRACE = Duration.ofMinutes(30);

	/**
	 * The latest the check is made before the start, so that a policy checking at the start (or
	 * with booking open until the start) still gets a job run before the class begins.
	 */
	static final Duration MIN_NOTICE = Duration.ofMinutes(5);

	/** How far ahead classes are looked at; the checks are expected within a week of the start. */
	static final Duration LOOKAHEAD = Duration.ofDays(7);

	private static final Logger log = LoggerFactory.getLogger(DefaultClassAutoCancellation.class);

	private final ScheduledEvents events;

	private final BookingPolicies policies;

	private final Settlement settlement;

	DefaultClassAutoCancellation(ScheduledEvents events, BookingPolicies policies, Settlement settlement) {
		this.events = events;
		this.policies = policies;
		this.settlement = settlement;
	}

	@Override
	public int cancelDue(Instant now) {
		int cancelled = 0;
		for (AutoCancelCandidate candidate : this.events.findAutoCancelCandidates(now, now.plus(LOOKAHEAD))) {
			BookingPolicies.Values rules = this.policies
				.forOccurrence(candidate.serviceTypeId(), candidate.trainingGroupId(), candidate.id())
				.effective();
			if (!isDue(candidate.startsAt(), rules, now)) {
				continue;
			}
			try {
				if (this.settlement.settle(candidate.id(), now)) {
					cancelled++;
				}
			}
			catch (RuntimeException ex) {
				log.warn("Could not check the participants of class {} for automatic cancellation", candidate.id(), ex);
			}
		}
		return cancelled;
	}

	/**
	 * When the participants of a class are counted: {@code autoCancelHoursBefore} before the start,
	 * or when booking closes ({@code bookingClosesMinutesBefore}) when that is not set, but at least
	 * {@link #MIN_NOTICE} before the start.
	 */
	static Instant checkMoment(Instant startsAt, BookingPolicies.Values rules) {
		Duration before = (rules.autoCancelHoursBefore() != null) ? Duration.ofHours(rules.autoCancelHoursBefore())
				: (rules.bookingClosesMinutesBefore() != null) ? Duration.ofMinutes(rules.bookingClosesMinutesBefore())
				: Duration.ZERO;
		return startsAt.minus((before.compareTo(MIN_NOTICE) < 0) ? MIN_NOTICE : before);
	}

	/** Whether the rule is on for the class and its check is due at {@code now}. */
	static boolean isDue(Instant startsAt, BookingPolicies.Values rules, Instant now) {
		if (!Boolean.TRUE.equals(rules.autoCancelEnabled()) || rules.autoCancelMinParticipants() == null) {
			return false;
		}
		Instant moment = checkMoment(startsAt, rules);
		return !now.isBefore(moment) && now.isBefore(moment.plus(GRACE)) && now.isBefore(startsAt);
	}

	/**
	 * Settles one class in its own transaction. Lock order as in booking: occurrence, reservations,
	 * waiting-list entries, then passes in ascending id order.
	 */
	@Component
	static class Settlement {

		/** {@code reservation.cancel_kind} of a reservation of a class the studio cancelled. */
		static final String STUDIO = "STUDIO";

		private final GroupClassSchedule schedule;

		private final ParticipationRules rules;

		private final ReservationStore reservations;

		private final WaitlistStore waitlist;

		private final BookingEntitlements entitlements;

		private final MakeupEntitlements makeups;

		private final ScheduledEvents events;

		Settlement(GroupClassSchedule schedule, ParticipationRules rules, ReservationStore reservations,
				WaitlistStore waitlist, BookingEntitlements entitlements, MakeupEntitlements makeups,
				ScheduledEvents events) {
			this.schedule = schedule;
			this.rules = rules;
			this.reservations = reservations;
			this.waitlist = waitlist;
			this.entitlements = entitlements;
			this.makeups = makeups;
			this.events = events;
		}

		/**
		 * Counts the class's participants again under its lock, with its current time and policy, and
		 * cancels it when there are fewer than the minimum.
		 *
		 * @return whether the class was cancelled
		 */
		@Transactional
		public boolean settle(UUID eventId, Instant now) {
			Occurrence event;
			try {
				event = this.schedule.lockIncludingPrivate(eventId);
			}
			catch (ApiException ex) {
				return false;
			}
			BookingPolicies.Values policy = this.rules.terms(event).rules();
			if (!"SCHEDULED".equals(event.status()) || !isDue(event.startsAt(), policy, now)) {
				return false;
			}
			List<Reservation> active = this.reservations.lockActiveInEvent(eventId);
			int minimum = policy.autoCancelMinParticipants();
			if (active.size() >= minimum) {
				return false;
			}
			List<WaitlistEntry> waiting = this.waitlist.lockOpenInEvent(eventId);
			Set<UUID> participants = new LinkedHashSet<>();
			active.forEach((reservation) -> participants.add(reservation.customerId()));
			waiting.forEach((entry) -> participants.add(entry.customerId()));
			// Refused when an administrator forced the class to run since it was read.
			if (!this.events.cancelForTooFewParticipants(eventId, participants, active.size(), minimum)) {
				return false;
			}
			for (WaitlistEntry entry : waiting) {
				this.waitlist.cancel(entry.id(), now);
			}
			List<Reservation> byPass = active.stream()
				.sorted(Comparator.comparing(Reservation::customerPassId, Comparator.nullsLast(Comparator.naturalOrder()))
					.thenComparing(Reservation::id))
				.toList();
			for (Reservation reservation : byPass) {
				this.reservations.cancel(reservation, null, now, returnEntry(reservation, now), STUDIO);
			}
			return true;
		}

		/** Gives the participant back what the reservation used; null when it used nothing. */
		private Boolean returnEntry(Reservation reservation, Instant now) {
			if (reservation.customerPassId() != null) {
				this.entitlements.release(reservation.customerId(), reservation.customerPassId(), reservation.id(), true,
						null);
				return true;
			}
			if (reservation.makeupCreditId() != null) {
				boolean returned = this.makeups.canReturn(reservation.customerId(), reservation.makeupCreditId(),
						reservation.id(), now);
				this.makeups.release(reservation.customerId(), reservation.makeupCreditId(), reservation.id(), true,
						returned);
				return returned;
			}
			return null;
		}

	}

}
