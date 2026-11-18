package studioforyou.pl.backend.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;

import studioforyou.pl.backend.booking.internal.ParticipationRules.Terms;
import studioforyou.pl.backend.booking.WaitlistPlaceAllocated;
import studioforyou.pl.backend.booking.internal.WaitlistStore.WaitlistEntry;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.BookingEntitlements.EligiblePass;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Gives freed places of a group class to the participants waiting for them, in the transaction
 * that freed the places: nobody outside the queue can take such a place in between. With the
 * policy's {@code waitlistConfirmMinutes} at 0 the place is booked at once; with a positive value
 * it is held as an offer the participant confirms in that time, and an offer that runs out passes
 * the place on. A waiting participant who could not book right now is skipped and keeps their
 * place in the queue.
 */
@Component
class WaitlistPromotion {

	private final WaitlistStore waitlist;

	private final ReservationStore reservations;

	private final CustomerProfiles customers;

	private final BookingEntitlements entitlements;

	private final ParticipationRules rules;

	private final ApplicationEventPublisher events;

	WaitlistPromotion(WaitlistStore waitlist, ReservationStore reservations, CustomerProfiles customers,
			BookingEntitlements entitlements, ParticipationRules rules, ApplicationEventPublisher events) {
		this.waitlist = waitlist;
		this.reservations = reservations;
		this.customers = customers;
		this.entitlements = entitlements;
		this.rules = rules;
		this.events = events;
	}

	/**
	 * Fills the event's free places from its queue, after closing offers that have run out. The
	 * caller holds the occurrence lock and has already freed its place, but not yet run its own
	 * pass operations: a cancellation releases one participant's pass and a promotion reserves
	 * another's, so two such transactions could each hold the pass the other one needs. Every pass
	 * operation of the transaction therefore runs here, the caller's {@code passSteps} included,
	 * in ascending pass id order.
	 *
	 * @param leavingCustomerId the participant who has just given up the place, never given it back;
	 * or {@code null}
	 */
	void fillFreePlaces(Occurrence event, UUID leavingCustomerId, Instant now, List<PassStep> passSteps) {
		this.waitlist.expireOffers(event.id(), now);
		Terms terms = this.rules.terms(event);
		List<Promotion> promotions = candidates(event, terms, leavingCustomerId, now);
		List<PassStep> steps = new ArrayList<>(passSteps);
		int confirmMinutes = confirmMinutes(terms);
		if (confirmMinutes > 0) {
			// The place waits for the participant's confirmation, at most until booking closes.
			Instant closes = event.startsAt().minus(Duration.ofMinutes(terms.rules().bookingClosesMinutesBefore()));
			Instant offerEnds = now.plus(Duration.ofMinutes(confirmMinutes));
			for (Promotion promotion : promotions) {
				WaitlistEntry offered = this.waitlist.offer(promotion.entry().id(),
						offerEnds.isBefore(closes) ? offerEnds : closes);
				this.events.publishEvent(new WaitlistPlaceAllocated(offered.id(), offered.customerId(),
						event.id(), null, offered.offerExpiresAt()));
			}
			promotions = List.of();
		}
		for (Promotion promotion : promotions) {
			// Insert before the ledger FK, but attach funding only after its owner's lock, as booking does.
			insertReservation(event, promotion, promotion.entry().addedBy());
			steps.add(new PassStep(promotion.passId(), () -> reserve(event, promotion, promotion.entry().addedBy())));
		}
		steps.sort(Comparator.comparing(PassStep::passId));
		steps.forEach(step -> step.action().run());
		for (Promotion promotion : promotions) {
			this.reservations.attachPass(promotion.reservationId(), promotion.passId());
			this.waitlist.resolve(promotion.entry().id(), WaitlistStore.PROMOTED, promotion.reservationId(), now);
			this.events.publishEvent(new WaitlistPlaceAllocated(promotion.entry().id(), promotion.entry().customerId(),
					event.id(), promotion.reservationId(), null));
		}
	}

	/**
	 * Books the place held for the participant's offer. The caller holds the occurrence lock and
	 * the entry, and has checked that the offer is still open; the participant has to meet the
	 * booking rules now. This transaction locks only this participant's pass.
	 */
	UUID confirmOffer(Occurrence event, WaitlistEntry entry, ProfileDetails participant, UUID actorId, Instant now) {
		Terms terms = this.rules.terms(event);
		List<EligiblePass> passes = this.rules.passes(entry.customerId(), event, terms.date());
		String reason = ParticipationRules.windowReason(event, terms.rules(), now);
		if (reason == null) {
			reason = this.rules.participantReason(participant, event, terms.date(), passes);
		}
		if (reason != null) {
			throw ParticipationRules.rejected(reason);
		}
		Promotion promotion = new Promotion(entry, UUID.randomUUID(),
				ParticipationRules.fundingPass(entry.customerPassId(), passes).id(), terms);
		insertReservation(event, promotion, actorId);
		this.entitlements.reserve(entry.customerId(), promotion.passId(), event.serviceTypeId(), terms.date(),
				promotion.reservationId(), actorId);
		this.reservations.attachPass(promotion.reservationId(), promotion.passId());
		this.waitlist.resolve(entry.id(), WaitlistStore.PROMOTED, promotion.reservationId(), now);
		return promotion.reservationId();
	}

	/**
	 * Before a booking or a join counts the free places: offers that ran out pass their place on.
	 * Only offers are made here, so no pass is locked before the caller's own. When the policy has
	 * meanwhile switched to immediate promotion, {@link WaitlistOfferExpiry} gives such a place.
	 */
	void passOnExpiredOffers(Occurrence event, Instant now) {
		if (this.waitlist.expireOffers(event.id(), now) > 0 && confirmMinutes(this.rules.terms(event)) > 0) {
			fillFreePlaces(event, null, now, List.of());
		}
	}

	/** Minutes to confirm an offered place; 0 gives freed places at once. */
	static int confirmMinutes(Terms terms) {
		Integer minutes = terms.rules().waitlistConfirmMinutes();
		return minutes == null ? 0 : Math.max(0, minutes);
	}

	/** The first waiting participants, in queue order, who meet the booking rules now. */
	private List<Promotion> candidates(Occurrence event, Terms terms, UUID leavingCustomerId, Instant now) {
		int free = event.capacity() == null ? Integer.MAX_VALUE
				: event.capacity() - this.reservations.activeCount(event.id()) - this.waitlist.heldOffers(event.id(), now);
		if (free <= 0 || ParticipationRules.windowReason(event, terms.rules(), now) != null) {
			return List.of();
		}
		List<WaitlistEntry> queue = this.waitlist.lockQueue(event.id());
		if (queue.isEmpty()) {
			return List.of();
		}
		Map<UUID, ProfileDetails> participants = this.customers
			.findAllById(queue.stream().map(WaitlistEntry::customerId).toList()).stream()
			.collect(Collectors.toMap(ProfileDetails::id, Function.identity()));
		List<Promotion> chosen = new ArrayList<>();
		for (WaitlistEntry entry : queue) {
			if (chosen.size() == free) {
				break;
			}
			ProfileDetails participant = participants.get(entry.customerId());
			if (participant == null || entry.customerId().equals(leavingCustomerId)) {
				continue;
			}
			List<EligiblePass> passes = this.rules.passes(entry.customerId(), event, terms.date());
			if (this.rules.promotionReason(entry, participant, event, terms, passes, now) == null) {
				chosen.add(new Promotion(entry, UUID.randomUUID(),
						ParticipationRules.fundingPass(entry.customerPassId(), passes).id(), terms));
			}
		}
		return chosen;
	}

	private void insertReservation(Occurrence event, Promotion promotion, UUID bookedBy) {
		var policy = promotion.terms().rules();
		this.reservations.insert(promotion.reservationId(), event.id(), promotion.entry().customerId(), bookedBy,
				"WAITLIST", promotion.terms().policy().policyId(), promotion.terms().freeCancelUntil(event),
				policy.cancellationAllowed(), policy.lateCancelAllowed(), policy.lateCancelForfeitsEntry());
	}

	private void reserve(Occurrence event, Promotion promotion, UUID actorId) {
		WaitlistEntry entry = promotion.entry();
		try {
			this.entitlements.reserve(entry.customerId(), promotion.passId(), event.serviceTypeId(),
					promotion.terms().date(), promotion.reservationId(), actorId);
		}
		catch (ApiException ex) {
			// The pass changed after the check above. The transaction is already marked for rollback,
			// so the whole cancellation is refused with nothing changed; a retry decides again.
			throw new ApiException(ErrorCode.VERSION_CONFLICT,
					"The class changed while its freed place was being given to the waiting list. Refresh it and try again.");
		}
	}

	/** A pass operation of the transaction; all of them run in ascending {@code passId} order. */
	record PassStep(UUID passId, Runnable action) {
	}

	private record Promotion(WaitlistEntry entry, UUID reservationId, UUID passId, Terms terms) {
	}

}
