package studioforyou.pl.backend.booking.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.internal.GroupClassDtos.WaitlistEntryResponse;
import studioforyou.pl.backend.booking.internal.ParticipationRules.Terms;
import studioforyou.pl.backend.booking.internal.WaitlistStore.WaitlistEntry;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.BookingEntitlements.EligiblePass;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * The self-service waiting list of a full group class. Joining charges nothing: a freed place and
 * its pass entry are given by {@link WaitlistPromotion} in the transaction that frees the place.
 * Every change takes the occurrence lock first, like booking.
 */
@Service
@Transactional
class GroupClassWaitlistService {

	private final WaitlistStore waitlist;

	private final ReservationStore reservations;

	private final GroupClassSchedule schedule;

	private final ParticipationRules rules;

	private final WaitlistPromotion promotion;

	private final Clock clock = Clock.systemUTC();

	GroupClassWaitlistService(WaitlistStore waitlist, ReservationStore reservations, GroupClassSchedule schedule,
			ParticipationRules rules, WaitlistPromotion promotion) {
		this.waitlist = waitlist;
		this.reservations = reservations;
		this.schedule = schedule;
		this.rules = rules;
		this.promotion = promotion;
	}

	JoinResult join(UUID actorId, UUID profileId, UUID eventId, UUID customerPassId) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		Occurrence event = this.schedule.lock(eventId);
		// Joins wait for the occurrence lock, so the queue follows the order in which they got it.
		Instant now = this.clock.instant();
		if (!this.reservations.ownActive(profileId, List.of(eventId)).isEmpty()) {
			throw ParticipationRules.rejected("ALREADY_RESERVED");
		}
		WaitlistEntry existing = this.waitlist.lockOpen(profileId, eventId).orElse(null);
		if (existing != null) {
			return new JoinResult(response(existing, profile, event, now), false);
		}
		this.promotion.passOnExpiredOffers(event, now);
		Terms terms = this.rules.terms(event);
		List<EligiblePass> passes = this.rules.passes(profileId, event, terms.date());
		String reason = ParticipationRules.windowReason(event, terms.rules(), now);
		if (reason == null && !Boolean.TRUE.equals(terms.rules().waitlistEnabled())) {
			reason = "WAITLIST_DISABLED";
		}
		if (reason == null && (event.capacity() == null
				|| this.reservations.activeCount(eventId) + this.waitlist.heldOffers(eventId, now) < event.capacity())) {
			reason = "CLASS_NOT_FULL";
		}
		if (reason == null) {
			reason = this.rules.participantReason(profile, event, terms.date(), passes);
		}
		if (reason != null) {
			throw ParticipationRules.rejected(reason);
		}
		if (passes.stream().noneMatch(pass -> pass.id().equals(customerPassId))) {
			throw new ApiException(ErrorCode.PASS_NOT_USABLE, "The chosen pass cannot fund this class.");
		}
		WaitlistEntry created = this.waitlist.insert(UUID.randomUUID(), eventId, profileId, actorId, customerPassId, now);
		return new JoinResult(response(created, profile, event, now), true);
	}

	@Transactional(readOnly = true)
	WaitlistEntryResponse get(UUID actorId, UUID profileId, UUID entryId) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		WaitlistEntry entry = requireEntry(profileId, entryId);
		Occurrence event = this.schedule.find(entry.eventId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Group class not found."));
		return response(entry, profile, event, this.clock.instant());
	}

	/**
	 * Books the place offered to the participant from the list. Repeating it returns the entry with
	 * its reservation; an offer that ran out is refused, and its place goes to the next participant.
	 */
	WaitlistEntryResponse confirm(UUID actorId, UUID profileId, UUID entryId) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		WaitlistEntry reference = requireEntry(profileId, entryId);
		Occurrence event = this.schedule.lock(reference.eventId());
		WaitlistEntry entry = this.waitlist.lock(profileId, entryId).orElseThrow(GroupClassWaitlistService::notFound);
		Instant now = this.clock.instant();
		if (WaitlistStore.PROMOTED.equals(entry.status())) {
			return response(entry, profile, event, now);
		}
		if (WaitlistStore.EXPIRED.equals(entry.status())
				|| (WaitlistStore.OFFERED.equals(entry.status()) && !now.isBefore(entry.offerExpiresAt()))) {
			throw new ApiException(ErrorCode.WAITLIST_OFFER_EXPIRED,
					"The time to confirm the offered place has run out.");
		}
		if (!WaitlistStore.OFFERED.equals(entry.status())) {
			throw new ApiException(ErrorCode.WAITLIST_ENTRY_NOT_OFFERED,
					"No place is offered to the participant: status " + entry.status() + ".");
		}
		this.promotion.confirmOffer(event, entry, profile, actorId, now);
		return response(this.waitlist.lock(profileId, entryId).orElseThrow(), profile, event, now);
	}

	/**
	 * Leaving is idempotent; a participant who already got a place cancels the reservation instead.
	 * Declining an offered place passes it on to the next participant.
	 */
	WaitlistEntryResponse leave(UUID actorId, UUID profileId, UUID entryId) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		// Read only the occurrence ID first, then take the agreed locks in owner order.
		WaitlistEntry reference = requireEntry(profileId, entryId);
		Occurrence event = this.schedule.lock(reference.eventId());
		WaitlistEntry entry = this.waitlist.lock(profileId, entryId).orElseThrow(GroupClassWaitlistService::notFound);
		Instant now = this.clock.instant();
		if (WaitlistStore.OFFERED.equals(entry.status())) {
			entry = this.waitlist.cancel(entry.id(), now);
			this.promotion.fillFreePlaces(event, profileId, now, List.of());
		}
		else if (WaitlistStore.WAITING.equals(entry.status())) {
			entry = this.waitlist.cancel(entry.id(), now);
		}
		else if (!WaitlistStore.CANCELLED.equals(entry.status())) {
			throw new ApiException(ErrorCode.WAITLIST_ENTRY_NOT_WAITING,
					"The participant no longer waits for this class: status " + entry.status() + ".");
		}
		return response(entry, profile, event, now);
	}

	private WaitlistEntryResponse response(WaitlistEntry entry, ProfileDetails profile, Occurrence event, Instant now) {
		boolean waiting = WaitlistStore.WAITING.equals(entry.status());
		if (!waiting && !WaitlistStore.OFFERED.equals(entry.status())) {
			return WaitlistEntryResponse.of(entry, null, null);
		}
		Terms terms = this.rules.terms(event);
		List<EligiblePass> passes = this.rules.passes(profile.id(), event, terms.date());
		return WaitlistEntryResponse.of(entry, waiting ? this.waitlist.position(entry) : null,
				this.rules.promotionReason(entry, profile, event, terms, passes, now));
	}

	private WaitlistEntry requireEntry(UUID profileId, UUID entryId) {
		return this.waitlist.find(profileId, entryId).orElseThrow(GroupClassWaitlistService::notFound);
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Waiting list entry not found.");
	}

	record JoinResult(WaitlistEntryResponse entry, boolean created) {
	}

}
