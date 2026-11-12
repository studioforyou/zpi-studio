package studioforyou.pl.backend.booking.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.internal.GroupClassDtos.BookingAvailability;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancelRequest;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancellationAvailability;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.EligiblePassResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.GroupClassResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.HistoryEntryResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.InstructorResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ReservationResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.RoomResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ParticipantResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.WaitlistAvailability;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.WaitlistEntryResponse;
import studioforyou.pl.backend.booking.internal.ParticipationRules.Terms;
import studioforyou.pl.backend.booking.internal.ReservationStore.PastReservation;
import studioforyou.pl.backend.booking.internal.ReservationStore.Reservation;
import studioforyou.pl.backend.booking.internal.WaitlistStore.QueuedEntry;
import studioforyou.pl.backend.booking.internal.WaitlistStore.WaitlistEntry;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.BookingEntitlements.EligiblePass;
import studioforyou.pl.backend.membership.MakeupEntitlements;
import studioforyou.pl.backend.membership.MakeupEntitlements.Credit;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * Coordinates self-service participation in one transaction. Lock order is occurrence,
 * reservations, waiting-list entries, then passes in ascending id order; each participating
 * module accesses only its own persistence. Participants book public classes only; a private
 * class an administrator booked them on (#110) is shown and can be cancelled like the others.
 */
@Service
@Transactional
class GroupClassBookingService {

	private static final Duration MAX_WINDOW = Duration.ofDays(93);

	private final ReservationStore reservations;

	private final WaitlistStore waitlist;

	private final GroupClassSchedule schedule;

	private final CustomerProfiles customers;

	private final BookingEntitlements entitlements;

	private final MakeupEntitlements makeups;

	private final ParticipationRules rules;

	private final WaitlistPromotion promotion;

	private final Clock clock = Clock.systemUTC();

	GroupClassBookingService(ReservationStore reservations, WaitlistStore waitlist, GroupClassSchedule schedule,
			CustomerProfiles customers, BookingEntitlements entitlements, ParticipationRules rules,
			WaitlistPromotion promotion, MakeupEntitlements makeups) {
		this.reservations = reservations;
		this.waitlist = waitlist;
		this.schedule = schedule;
		this.customers = customers;
		this.entitlements = entitlements;
		this.makeups = makeups;
		this.rules = rules;
		this.promotion = promotion;
	}

	@Transactional(readOnly = true)
	List<GroupClassResponse> list(UUID actorId, UUID profileId, Instant from, Instant to, boolean onlyOwn) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		requireWindow(from, to);
		Instant now = this.clock.instant();
		// The participant's own reservations include private classes an administrator booked.
		List<Occurrence> occurrences = (onlyOwn ? this.schedule.listIncludingPrivate(from, to)
				: this.schedule.list(from, to)).stream()
			.filter(event -> !onlyOwn || event.startsAt().isAfter(now)).toList();
		List<UUID> ids = occurrences.stream().map(Occurrence::id).toList();
		Map<UUID, Reservation> own = this.reservations.ownActive(profileId, ids);
		Map<UUID, Integer> counts = this.reservations.activeCounts(ids);
		Map<UUID, Integer> held = this.waitlist.heldOffers(ids, now);
		Map<UUID, Integer> waiting = this.waitlist.waitingCounts(ids);
		Map<UUID, QueuedEntry> queued = this.waitlist.ownWaiting(profileId, ids);
		return occurrences.stream().filter(event -> !onlyOwn || own.containsKey(event.id()))
			.map(event -> project(profile, event, own.get(event.id()), counts.getOrDefault(event.id(), 0),
					held.getOrDefault(event.id(), 0), waiting.getOrDefault(event.id(), 0), queued.get(event.id()), now))
			.toList();
	}

	@Transactional(readOnly = true)
	MakeupOffer listMakeup(UUID actorId, UUID profileId, UUID creditId, Instant from, Instant to) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		requireWindow(from, to);
		Credit credit = this.makeups.available(profileId, creditId);
		Instant now = this.clock.instant();
		List<Occurrence> events = this.schedule.list(from, to).stream()
			.filter(event -> event.startsAt().isAfter(now) && credit.covers(event.serviceTypeId(), event.startsAt()))
			.sorted(Comparator.comparing(Occurrence::startsAt).thenComparing(Occurrence::id)).toList();
		List<UUID> ids = events.stream().map(Occurrence::id).toList();
		Map<UUID, Reservation> own = this.reservations.ownActive(profileId, ids);
		Map<UUID, Integer> counts = this.reservations.activeCounts(ids);
		Map<UUID, Integer> held = this.waitlist.heldOffers(ids, now);
		Map<UUID, Integer> waiting = this.waitlist.waitingCounts(ids);
		Map<UUID, QueuedEntry> queued = this.waitlist.ownWaiting(profileId, ids);
		return new MakeupOffer(credit.id(), credit.productName(), credit.expiresAt(), events.stream()
			.map(event -> project(profile, event, own.get(event.id()), counts.getOrDefault(event.id(), 0),
					held.getOrDefault(event.id(), 0), waiting.getOrDefault(event.id(), 0), queued.get(event.id()), now, credit))
			.toList());
	}

	/** Booked events that started in [from, to), latest first, with what became of each booking. */
	@Transactional(readOnly = true)
	List<HistoryEntryResponse> history(UUID actorId, UUID profileId, Instant from, Instant to) {
		this.rules.participant(actorId, profileId);
		requireWindow(from, to);
		Instant now = this.clock.instant();
		if (!from.isBefore(now)) {
			return List.of();
		}
		Map<UUID, PastReservation> booked = this.reservations.history(profileId).stream()
			.collect(Collectors.toMap(PastReservation::eventId, Function.identity()));
		return this.schedule.findStartingBetween(booked.keySet(), from, to.isAfter(now) ? now : to).stream()
			.map(event -> historyEntry(event, booked.get(event.id())))
			.toList();
	}

	@Transactional(readOnly = true)
	GroupClassResponse get(UUID actorId, UUID profileId, UUID eventId) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		Occurrence event = requireVisibleEvent(profileId, eventId);
		List<UUID> ids = List.of(eventId);
		Reservation own = this.reservations.ownActive(profileId, ids).get(eventId);
		Instant now = this.clock.instant();
		return project(profile, event, own, this.reservations.activeCount(eventId), this.waitlist.heldOffers(eventId, now),
				this.waitlist.waitingCounts(ids).getOrDefault(eventId, 0), this.waitlist.ownWaiting(profileId, ids).get(eventId),
				now);
	}

	@Transactional(readOnly = true)
	ReservationResponse getReservation(UUID actorId, UUID profileId, UUID reservationId) {
		this.rules.participant(actorId, profileId);
		Reservation reservation = requireReservation(profileId, reservationId);
		return reservationResponse(reservation, requireBookedEvent(reservation.eventId()), this.clock.instant());
	}

	@Transactional(readOnly = true)
	List<ParticipantResponse> participants(UUID actorId, UUID profileId, UUID eventId) {
		this.rules.participant(actorId, profileId);
		requireVisibleEvent(profileId, eventId);
		List<UUID> ids = this.reservations.activeParticipantIdsForBookedProfile(profileId, eventId);
		if (ids.isEmpty()) {
			throw new ApiException(ErrorCode.ACCESS_DENIED,
					"The participant list is available to participants with an active reservation.");
		}
		Map<UUID, String> names = this.customers.displayNames(ids);
		return ids.stream().filter(names::containsKey)
			.map(id -> new ParticipantResponse(names.get(id), id.equals(profileId)))
			.sorted(Comparator.comparing(ParticipantResponse::displayName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ParticipantResponse::isSelf, Comparator.reverseOrder()))
			.toList();
	}

	BookingResult book(UUID actorId, UUID profileId, UUID eventId, UUID customerPassId) {
		return book(actorId, profileId, eventId, customerPassId, null);
	}

	BookingResult bookMakeup(UUID actorId, UUID profileId, UUID eventId, UUID creditId) {
		return book(actorId, profileId, eventId, null, creditId);
	}

	private BookingResult book(UUID actorId, UUID profileId, UUID eventId, UUID customerPassId, UUID creditId) {
		ProfileDetails profile = this.rules.participant(actorId, profileId);
		Occurrence event = this.schedule.lock(eventId);
		Instant now = this.clock.instant();
		Reservation existing = this.reservations.lockActive(profileId, eventId).orElse(null);
		if (existing != null) {
			if (creditId != null && !creditId.equals(existing.makeupCreditId())) {
				throw new ApiException(ErrorCode.ALREADY_RESERVED, "This class is already reserved using another entitlement.");
			}
			return new BookingResult(reservationResponse(existing, event, now), false);
		}
		Credit credit = creditId == null ? null : this.makeups.available(profileId, creditId);
		// Places of offers that ran out go to the queue before anyone else can book them.
		this.promotion.passOnExpiredOffers(event, now);
		Terms terms = this.rules.terms(event);
		BookingPolicies.Values rules = terms.rules();
		String reason = ParticipationRules.windowReason(event, rules, now);
		if (reason == null && event.capacity() != null
				&& this.reservations.activeCount(eventId) + this.waitlist.heldOffers(eventId, now) >= event.capacity()) {
			reason = "CLASS_FULL";
		}
		if (reason == null) {
			reason = credit == null
					? this.rules.participantReason(profile, event, terms.date(), this.rules.passes(profileId, event, terms.date()))
					: !credit.covers(event.serviceTypeId(), event.startsAt()) ? "MAKEUP_CREDIT_NOT_USABLE"
					: this.rules.participantReason(profile, event, terms.date(), true);
		}
		if (reason != null) {
			throw ParticipationRules.rejected(reason);
		}
		// A participant waiting for this class who books a free place leaves the queue with it.
		WaitlistEntry waiting = this.waitlist.lockOpen(profileId, eventId).orElse(null);
		UUID reservationId = UUID.randomUUID();
		boolean ownProfile = this.customers.findOwnProfile(actorId)
			.map(own -> own.id().equals(profileId)).orElse(false);
		this.reservations.insert(reservationId, eventId, profileId, actorId,
				ownProfile ? "SELF" : "GUARDIAN", terms.policy().policyId(), terms.freeCancelUntil(event),
				rules.cancellationAllowed(), rules.lateCancelAllowed(), rules.lateCancelForfeitsEntry());
		// Insert before the ledger FK, but attach funding only after its owner's validation/lock.
		// Otherwise concurrent inserts can both take pass FK KEY SHARE locks before upgrading them.
		Reservation created;
		if (credit == null) {
			this.entitlements.reserve(profileId, customerPassId, event.serviceTypeId(), terms.date(), reservationId, actorId);
			created = this.reservations.attachPass(reservationId, customerPassId);
		}
		else {
			this.makeups.reserve(profileId, creditId, event.serviceTypeId(), event.startsAt(), reservationId);
			created = this.reservations.attachMakeup(reservationId, creditId);
		}
		if (waiting != null) {
			this.waitlist.resolve(waiting.id(), WaitlistStore.BOOKED, reservationId, now);
		}
		return new BookingResult(reservationResponse(created, event, now), true);
	}

	ReservationResponse cancel(UUID actorId, UUID profileId, UUID reservationId, CancelRequest request) {
		this.rules.participant(actorId, profileId);
		// Read only the occurrence ID first, then take the agreed locks in owner order.
		Reservation reference = requireReservation(profileId, reservationId);
		Occurrence event = this.schedule.lockIncludingPrivate(reference.eventId());
		Reservation reservation = this.reservations.lock(profileId, reservationId)
			.orElseThrow(GroupClassBookingService::notFoundReservation);
		Instant now = this.clock.instant();
		if ("CANCELLED".equals(reservation.status())) {
			return reservationResponse(reservation, event, now);
		}
		if (reservation.version() != request.expectedVersion()) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT, "The reservation has changed. Refresh it before cancelling.");
		}
		CancellationAvailability consequence = cancellationFor(reservation, event, now);
		if (!consequence.allowed()) {
			throw ParticipationRules.rejected(consequence.reason());
		}
		if (!Objects.equals(request.expectedEntryReturned(), consequence.entryReturned())) {
			throw new ApiException(ErrorCode.CANCELLATION_CONSEQUENCE_CHANGED,
					"The cancellation consequence has changed. Refresh the class and confirm it again.",
					// Without a pass there is no consequence to report.
					consequence.entryReturned() == null ? Map.of() : Map.of("entryReturned", consequence.entryReturned()));
		}
		String kind = now.isAfter(reservation.freeCancelUntil()) ? "LATE" : "IN_TIME";
		if (reservation.makeupCreditId() != null) {
			this.makeups.release(profileId, reservation.makeupCreditId(), reservation.id(),
					cancellation(reservation, event, now).entryReturned(), request.expectedEntryReturned());
		}
		Reservation cancelled = this.reservations.cancel(reservation, actorId, now, consequence.entryReturned(), kind);
		// The freed place goes to the waiting list in this transaction. The promotion also runs this
		// cancellation's own pass release, ordered together with the passes it reserves.
		UUID passId = reservation.customerPassId();
		this.promotion.fillFreePlaces(event, profileId, now, passId == null ? List.of()
				: List.of(new WaitlistPromotion.PassStep(passId, () -> this.entitlements.release(profileId, passId,
						reservation.id(), consequence.entryReturned(), actorId))));
		return reservationResponse(cancelled, event, now);
	}

	/** {@code heldOffers} are places offered from the waiting list, not available to others. */
	private GroupClassResponse project(ProfileDetails profile, Occurrence event, Reservation own, int activeCount,
			int heldOffers, int waitingCount, QueuedEntry queued, Instant now) {
		return project(profile, event, own, activeCount, heldOffers, waitingCount, queued, now, null);
	}

	private GroupClassResponse project(ProfileDetails profile, Occurrence event, Reservation own, int activeCount,
			int heldOffers, int waitingCount, QueuedEntry queued, Instant now, Credit credit) {
		Terms terms = this.rules.terms(event);
		BookingPolicies.Values policy = terms.rules();
		Integer availablePlaces = event.capacity() == null ? null
				: Math.max(0, event.capacity() - activeCount - heldOffers);
		List<EligiblePass> passes = credit == null ? this.rules.passes(profile.id(), event, terms.date()) : List.of();
		boolean full = availablePlaces != null && availablePlaces == 0;
		boolean waitlistEnabled = Boolean.TRUE.equals(policy.waitlistEnabled());
		WaitlistEntry entry = queued == null ? null : queued.entry();
		String window = ParticipationRules.windowReason(event, policy, now);
		// The participant's own checks cost queries, so they run only when an answer depends on them.
		String participant = own == null && window == null && (!full || entry != null || waitlistEnabled)
				? this.rules.participantReason(profile, event, terms.date(), credit != null || !passes.isEmpty()) : null;
		String booking = own != null ? "ALREADY_RESERVED" : window != null ? window : full ? "CLASS_FULL" : participant;
		String joining = own != null ? "ALREADY_RESERVED" : entry != null ? "WAITLIST_ALREADY_JOINED"
				: window != null ? window : !waitlistEnabled ? "WAITLIST_DISABLED" : !full ? "CLASS_NOT_FULL" : participant;
		WaitlistEntryResponse ownEntry = entry == null ? null : WaitlistEntryResponse.of(entry, queued.position(),
				this.rules.promotionReason(entry, profile, event, terms, passes, now));
		return new GroupClassResponse(event.id(), event.title(), event.serviceTypeId(), event.serviceTypeName(),
				event.calendarColor(), event.startsAt(), event.endsAt(), event.status(), event.capacity(), activeCount, availablePlaces,
				event.staff().stream().map(staff -> new InstructorResponse(staff.staffId(), staff.displayName(), staff.role())).toList(),
				event.resources().stream().map(room -> new RoomResponse(room.resourceId(), room.name())).toList(),
				own == null ? null : reservationResponse(own, event, now),
				new BookingAvailability(booking == null, booking, passes.stream().map(pass -> new EligiblePassResponse(
						pass.id(), pass.productName(), pass.entriesRemaining(), pass.validUntil())).toList()),
				new WaitlistAvailability(joining == null, joining, waitingCount, WaitlistPromotion.confirmMinutes(terms),
						ownEntry), event.version(), event.seriesChange());
	}

	private static HistoryEntryResponse historyEntry(Occurrence event, PastReservation reservation) {
		return new HistoryEntryResponse(reservation.id(), event.id(), event.title(), event.serviceTypeName(),
				event.calendarColor(), event.startsAt(), event.endsAt(),
				event.staff().stream().map(staff -> new InstructorResponse(staff.staffId(), staff.displayName(), staff.role())).toList(),
				event.resources().stream().map(room -> new RoomResponse(room.resourceId(), room.name())).toList(),
				outcome(reservation, event), reservation.entryReturned(), reservation.cancellationEffect());
	}

	/** The customer's own cancellation outranks the studio's, which outranks attendance. */
	static String outcome(PastReservation reservation, Occurrence event) {
		if ("CANCELLED".equals(reservation.status())) {
			return "LATE".equals(reservation.cancelKind()) ? "LATE_CANCELLED" : "CANCELLED";
		}
		if ("CANCELLED".equals(event.status())) {
			return "CLASS_CANCELLED";
		}
		return switch (reservation.attendance()) {
			case "PRESENT", "ABSENT" -> reservation.attendance();
			case null, default -> "UNMARKED";
		};
	}

	static CancellationAvailability cancellation(Reservation reservation, Occurrence event, Instant now) {
		if (!"ACTIVE".equals(reservation.status())) {
			return unavailableCancellation("RESERVATION_NOT_ACTIVE");
		}
		if (!event.startsAt().isAfter(now)) {
			return unavailableCancellation("EVENT_ALREADY_STARTED");
		}
		if (!"SCHEDULED".equals(event.status())) {
			return unavailableCancellation("EVENT_NOT_BOOKABLE");
		}
		if (reservation.cancellationAllowed() == null || reservation.lateCancelAllowed() == null
				|| reservation.lateCancelForfeitsEntry() == null || reservation.freeCancelUntil() == null) {
			return unavailableCancellation("CANCELLATION_TERMS_UNAVAILABLE");
		}
		if (!reservation.cancellationAllowed()) {
			return unavailableCancellation("CANCELLATION_NOT_ALLOWED");
		}
		boolean late = now.isAfter(reservation.freeCancelUntil());
		if (late && !reservation.lateCancelAllowed()) {
			return unavailableCancellation("FREE_CANCELLATION_EXPIRED");
		}
		// A place an administrator booked without a pass entry has no entry to return or forfeit.
		Boolean entryReturned = reservation.customerPassId() == null && reservation.makeupCreditId() == null ? null
				: !late || !reservation.lateCancelForfeitsEntry();
		return new CancellationAvailability(true, null, entryReturned);
	}

	private static CancellationAvailability unavailableCancellation(String reason) {
		return new CancellationAvailability(false, reason, null);
	}

	CancellationAvailability cancellationFor(Reservation reservation, Occurrence event, Instant now) {
		CancellationAvailability result = cancellation(reservation, event, now);
		if (result.allowed() && Boolean.TRUE.equals(result.entryReturned()) && reservation.makeupCreditId() != null) {
			return new CancellationAvailability(true, null, this.makeups.canReturn(reservation.customerId(),
					reservation.makeupCreditId(), reservation.id(), now));
		}
		return result;
	}

	private ReservationResponse reservationResponse(Reservation reservation, Occurrence event, Instant now) {
		return new ReservationResponse(reservation.id(), reservation.eventId(), reservation.customerId(),
				reservation.status(), reservation.customerPassId(), reservation.freeCancelUntil(), reservation.cancelledAt(),
				reservation.cancelKind(), reservation.entryReturned(), cancellationFor(reservation, event, now), reservation.version(),
				reservation.makeupCreditId());
	}

	/** A public class, or a private one the participant has an active reservation for. */
	private Occurrence requireVisibleEvent(UUID profileId, UUID eventId) {
		return this.schedule.find(eventId)
			.or(() -> this.schedule.findIncludingPrivate(eventId)
				.filter(event -> !this.reservations.ownActive(profileId, List.of(eventId)).isEmpty()))
			.orElseThrow(GroupClassBookingService::notFoundEvent);
	}

	/** The class of the participant's own reservation, whatever its visibility. */
	private Occurrence requireBookedEvent(UUID eventId) {
		return this.schedule.findIncludingPrivate(eventId).orElseThrow(GroupClassBookingService::notFoundEvent);
	}

	private static ApiException notFoundEvent() {
		return new ApiException(ErrorCode.NOT_FOUND, "Group class not found.");
	}

	private Reservation requireReservation(UUID profileId, UUID reservationId) {
		return this.reservations.find(profileId, reservationId)
			.orElseThrow(GroupClassBookingService::notFoundReservation);
	}

	private static ApiException notFoundReservation() {
		return new ApiException(ErrorCode.NOT_FOUND, "Reservation not found.");
	}

	private static void requireWindow(Instant from, Instant to) {
		if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE,
					"The time window must be positive and no longer than 93 days.");
		}
	}

	record BookingResult(ReservationResponse reservation, boolean created) {
	}

	record MakeupOffer(UUID creditId, String productName, Instant expiresAt, List<GroupClassResponse> items) {
	}

}
