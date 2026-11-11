package studioforyou.pl.backend.booking.internal;

import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.EligiblePassResponse;
import studioforyou.pl.backend.booking.internal.ParticipationRules.Terms;
import studioforyou.pl.backend.booking.internal.ReservationStore.Reservation;
import studioforyou.pl.backend.booking.internal.ReservationStore.RosterRow;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.BookingIssue;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.EventReservationResponse;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.EventReservationsResponse;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.StaffBookRequest;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.StaffBookingOptionsResponse;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.StaffCancelRequest;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.StaffCancellationResponse;
import studioforyou.pl.backend.booking.internal.WaitlistStore.QueuedEntry;
import studioforyou.pl.backend.booking.internal.WaitlistStore.WaitlistEntry;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.BookingEntitlements.EligiblePass;
import studioforyou.pl.backend.membership.MakeupEntitlements;
import studioforyou.pl.backend.membership.TraineePasses;
import studioforyou.pl.backend.membership.TraineePasses.Funding;
import studioforyou.pl.backend.membership.TraineePasses.FundingRequest;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * Administrators book and cancel group-class places for participants (#110), e.g. after a phone
 * call or at the desk, for public and private classes until they start.
 * <p>
 * The booking window does not bind them. They may book a full class or a participant who does not
 * meet the class's requirements once they confirm each of these issues; a participant without a
 * usable pass is then booked without a pass entry. The booking keeps the cancellation terms like a
 * participant's own, so the participant can cancel it in the app on those terms. When cancelling,
 * administrators decide whether the pass entry is returned, whatever the terms suggest, and may
 * cancel even when the participant could not. Each booking and cancellation is added to the audit
 * log with the optional reason.
 * <p>
 * Lock order is that of self-service: occurrence, reservations, waiting-list entries, then passes
 * in ascending id order.
 */
@Service
@Transactional
class StaffBookingService {

	static final String BOOKED = "RESERVATION_BOOKED_BY_STAFF";

	static final String CANCELLED = "RESERVATION_CANCELLED_BY_STAFF";

	private static final String MODULE = "booking";

	private static final String ENTITY_TYPE = "reservation";

	private static final String STAFF_SOURCE = "STAFF";

	private static final Comparator<EventReservationResponse> BY_NAME;

	static {
		Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
		BY_NAME = Comparator.comparing(EventReservationResponse::lastName, collator)
			.thenComparing(EventReservationResponse::firstName, collator)
			.thenComparing(EventReservationResponse::bookedAt);
	}

	private final ReservationStore reservations;

	private final WaitlistStore waitlist;

	private final GroupClassSchedule schedule;

	private final CustomerProfiles customers;

	private final BookingEntitlements entitlements;

	private final MakeupEntitlements makeups;

	private final TraineePasses passes;

	private final ParticipationRules rules;

	private final WaitlistPromotion promotion;

	private final StaffProfiles staffProfiles;

	private final AuditLog auditLog;

	private final Clock clock = Clock.systemUTC();

	StaffBookingService(ReservationStore reservations, WaitlistStore waitlist, GroupClassSchedule schedule,
			CustomerProfiles customers, BookingEntitlements entitlements, TraineePasses passes,
			ParticipationRules rules, WaitlistPromotion promotion, StaffProfiles staffProfiles, AuditLog auditLog,
			MakeupEntitlements makeups) {
		this.reservations = reservations;
		this.waitlist = waitlist;
		this.schedule = schedule;
		this.customers = customers;
		this.entitlements = entitlements;
		this.makeups = makeups;
		this.passes = passes;
		this.rules = rules;
		this.promotion = promotion;
		this.staffProfiles = staffProfiles;
		this.auditLog = auditLog;
	}

	@Transactional(readOnly = true)
	EventReservationsResponse roster(UUID eventId) {
		Occurrence event = requireEvent(eventId);
		Instant now = this.clock.instant();
		List<RosterRow> rows = this.reservations.roster(eventId);
		int held = this.waitlist.heldOffers(eventId, now);
		Integer available = event.capacity() == null ? null : Math.max(0, event.capacity() - rows.size() - held);
		String bookable = bookableReason(event, now);
		String clientReason = bookable != null ? null
				: ParticipationRules.windowReason(event, this.rules.terms(event).rules(), now);
		return new EventReservationsResponse(event.id(), event.status(), event.startsAt(), event.capacity(),
				rows.size(), held, available, this.waitlist.waitingCounts(List.of(eventId)).getOrDefault(eventId, 0),
				bookable == null, bookable, clientReason, responses(event, rows, now));
	}

	@Transactional(readOnly = true)
	EventReservationResponse reservation(UUID eventId, UUID reservationId) {
		Occurrence event = requireEvent(eventId);
		return response(event, this.reservations.rosterRow(eventId, reservationId)
			.orElseThrow(StaffBookingService::reservationNotFound), this.clock.instant());
	}

	@Transactional(readOnly = true)
	StaffBookingOptionsResponse options(UUID eventId, UUID customerId) {
		Occurrence event = requireEvent(eventId);
		ProfileDetails participant = requireParticipant(customerId);
		Instant now = this.clock.instant();
		Terms terms = this.rules.terms(event);
		List<EligiblePass> eligible = this.rules.passes(customerId, event, terms.date());
		Reservation own = this.reservations.ownActive(customerId, List.of(eventId)).get(eventId);
		QueuedEntry queued = this.waitlist.ownWaiting(customerId, List.of(eventId)).get(eventId);
		WaitlistEntry entry = queued == null ? null : queued.entry();
		String reason = own != null ? "ALREADY_RESERVED" : !participant.active() ? "CUSTOMER_NOT_ACTIVE"
				: bookableReason(event, now);
		List<BookingIssue> issues = reason != null ? List.of()
				: issues(participant, event, terms, eligible, this.reservations.activeCount(eventId),
						othersHeldOffers(eventId, entry, now));
		String clientReason = bookableReason(event, now) != null ? null
				: ParticipationRules.windowReason(event, terms.rules(), now);
		return new StaffBookingOptionsResponse(eventId, customerId, reason == null, reason, issues, clientReason,
				own == null ? null : own.id(), entry == null ? null : entry.status(), eligible.stream()
					.map(pass -> new EligiblePassResponse(pass.id(), pass.productName(), pass.entriesRemaining(),
							pass.validUntil()))
					.toList());
	}

	/**
	 * Books a place for the participant. A retry when the participant already has an active
	 * reservation returns it without charging another entry, like self-service.
	 */
	BookingResult book(UUID actorId, UUID eventId, StaffBookRequest request) {
		UUID customerId = request.customerId();
		Occurrence event = this.schedule.lockIncludingPrivate(eventId);
		ProfileDetails participant = requireParticipant(customerId);
		Instant now = this.clock.instant();
		Reservation existing = this.reservations.lockActive(customerId, eventId).orElse(null);
		if (existing != null) {
			return new BookingResult(response(event, this.reservations.rosterRow(eventId, existing.id()).orElseThrow(),
					now), false);
		}
		if (!participant.active()) {
			throw new ApiException(ErrorCode.CUSTOMER_NOT_ACTIVE, "The participant's profile is not active.");
		}
		String unbookable = bookableReason(event, now);
		if (unbookable != null) {
			throw ParticipationRules.rejected(unbookable);
		}
		// Places of offers that ran out go to the queue before anyone else can book them.
		this.promotion.passOnExpiredOffers(event, now);
		// A waiting participant booked by an administrator leaves the queue with the place, and a place
		// offered to them is the one they get.
		WaitlistEntry waiting = this.waitlist.lockOpen(customerId, eventId).orElse(null);
		Terms terms = this.rules.terms(event);
		List<EligiblePass> eligible = this.rules.passes(customerId, event, terms.date());
		List<BookingIssue> issues = issues(participant, event, terms, eligible,
				this.reservations.activeCount(eventId), othersHeldOffers(eventId, waiting, now));
		Set<BookingIssue> accepted = request.acceptedIssues() == null ? Set.of() : request.acceptedIssues();
		if (!accepted.containsAll(issues)) {
			throw new ApiException(ErrorCode.BOOKING_OVERRIDE_REQUIRED,
					"Confirm the issues of this booking before booking the place: " + issues + ".",
					Map.of("issues", issues));
		}
		UUID passId = request.customerPassId();
		if (passId == null && !eligible.isEmpty()) {
			throw ApiException.invalidField("customerPassId", FieldErrorCode.REQUIRED,
					"Choose one of the participant's eligible passes.");
		}
		if (passId != null && eligible.stream().noneMatch(pass -> pass.id().equals(passId))) {
			throw new ApiException(ErrorCode.PASS_NOT_USABLE, "The chosen pass cannot fund this class.");
		}
		UUID reservationId = UUID.randomUUID();
		BookingPolicies.Values policy = terms.rules();
		this.reservations.insert(reservationId, eventId, customerId, actorId, STAFF_SOURCE, terms.policy().policyId(),
				terms.freeCancelUntil(event), policy.cancellationAllowed(), policy.lateCancelAllowed(),
				policy.lateCancelForfeitsEntry());
		if (passId != null) {
			// Insert before the ledger FK, but attach funding only after its owner's lock, as booking does.
			this.entitlements.reserve(customerId, passId, event.serviceTypeId(), terms.date(), reservationId, actorId);
			this.reservations.attachPass(reservationId, passId);
		}
		if (waiting != null) {
			this.waitlist.resolve(waiting.id(), WaitlistStore.BOOKED, reservationId, now);
		}
		Map<String, AuditLog.Change> changes = new LinkedHashMap<>();
		changes.put("status", new AuditLog.Change(null, "ACTIVE"));
		changes.put("customerPassId", new AuditLog.Change(null, passId));
		changes.put("overriddenIssues", new AuditLog.Change(null, issues.stream().map(Enum::name).toList()));
		this.auditLog.append(new AuditLog.Entry(actorId, MODULE, ENTITY_TYPE, reservationId, BOOKED, changes,
				reason(request.reason())));
		return new BookingResult(response(event, this.reservations.rosterRow(eventId, reservationId).orElseThrow(), now),
				true);
	}

	/**
	 * Cancels the participant's place, returning the pass entry or not as the administrator decided.
	 * Repeating it returns the cancelled reservation unchanged. The freed place goes to the waiting
	 * list in the same transaction.
	 */
	EventReservationResponse cancel(UUID actorId, UUID eventId, UUID reservationId, StaffCancelRequest request) {
		Occurrence event = this.schedule.lockIncludingPrivate(eventId);
		Reservation reservation = this.reservations.lockInEvent(eventId, reservationId)
			.orElseThrow(StaffBookingService::reservationNotFound);
		Instant now = this.clock.instant();
		if ("CANCELLED".equals(reservation.status())) {
			return reservation(event, reservationId, now);
		}
		if (reservation.version() != request.expectedVersion()) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT, "The reservation has changed. Refresh it before cancelling.");
		}
		String unavailable = cancellationReason(reservation, event, now);
		if (unavailable != null) {
			throw ParticipationRules.rejected(unavailable);
		}
		UUID passId = reservation.customerPassId();
		UUID creditId = reservation.makeupCreditId();
		if ((passId != null || creditId != null) && request.returnEntry() == null) {
			throw ApiException.invalidField("returnEntry", FieldErrorCode.REQUIRED,
					"Decide whether the pass entry is returned.");
		}
		Boolean returned = passId == null && creditId == null ? null : request.returnEntry();
		if (creditId != null) {
			this.makeups.release(reservation.customerId(), creditId, reservationId, returned, returned);
		}
		String kind = isLate(reservation, now) ? "LATE" : "IN_TIME";
		this.reservations.cancel(reservation, actorId, now, returned, kind);
		UUID customerId = reservation.customerId();
		// The promotion runs this cancellation's pass release, ordered with the passes it reserves.
		this.promotion.fillFreePlaces(event, customerId, now, passId == null ? List.of()
				: List.of(new WaitlistPromotion.PassStep(passId,
						() -> this.entitlements.release(customerId, passId, reservationId, returned, actorId))));
		Map<String, AuditLog.Change> changes = new LinkedHashMap<>();
		changes.put("status", new AuditLog.Change("ACTIVE", "CANCELLED"));
		changes.put("entryReturned", new AuditLog.Change(suggestedEntryReturned(reservation, now), returned));
		this.auditLog.append(new AuditLog.Entry(actorId, MODULE, ENTITY_TYPE, reservationId, CANCELLED, changes,
				reason(request.reason())));
		return reservation(event, reservationId, now);
	}

	/** Why an administrator cannot book or cancel places of the class now, or null. */
	static String bookableReason(Occurrence event, Instant now) {
		return !"SCHEDULED".equals(event.status()) || !event.startsAt().isAfter(now) ? "EVENT_NOT_BOOKABLE" : null;
	}

	/** Why an administrator cannot cancel the place now, or null. */
	static String cancellationReason(Reservation reservation, Occurrence event, Instant now) {
		if (!"ACTIVE".equals(reservation.status())) {
			return "RESERVATION_NOT_ACTIVE";
		}
		if (!event.startsAt().isAfter(now)) {
			return "EVENT_ALREADY_STARTED";
		}
		return "SCHEDULED".equals(event.status()) ? null : "EVENT_NOT_BOOKABLE";
	}

	/**
	 * Whether the cancellation terms stored at booking return the entry now, as they would for the
	 * participant, also when the terms would not let the participant cancel. Null without a pass or
	 * stored terms.
	 */
	static Boolean suggestedEntryReturned(Reservation reservation, Instant now) {
		if ((reservation.customerPassId() == null && reservation.makeupCreditId() == null)
				|| reservation.freeCancelUntil() == null) {
			return null;
		}
		return !isLate(reservation, now) || Boolean.FALSE.equals(reservation.lateCancelForfeitsEntry());
	}

	private static boolean isLate(Reservation reservation, Instant now) {
		return reservation.freeCancelUntil() != null && now.isAfter(reservation.freeCancelUntil());
	}

	/** A full class first, then the participant's unmet requirements in the order self-service checks them. */
	private List<BookingIssue> issues(ProfileDetails participant, Occurrence event, Terms terms,
			List<EligiblePass> eligible, int active, int heldOffers) {
		List<BookingIssue> issues = new ArrayList<>();
		if (event.capacity() != null && active + heldOffers >= event.capacity()) {
			issues.add(BookingIssue.CLASS_FULL);
		}
		this.rules.unmetRequirements(participant, event, terms.date(), eligible)
			.forEach(code -> issues.add(BookingIssue.valueOf(code)));
		return issues;
	}

	/** Places held for other waiting participants; a place offered to this one is theirs to take. */
	private int othersHeldOffers(UUID eventId, WaitlistEntry own, Instant now) {
		int held = this.waitlist.heldOffers(eventId, now);
		boolean ownOffer = own != null && WaitlistStore.OFFERED.equals(own.status())
				&& own.offerExpiresAt() != null && own.offerExpiresAt().isAfter(now);
		return ownOffer ? held - 1 : held;
	}

	private EventReservationResponse reservation(Occurrence event, UUID reservationId, Instant now) {
		return response(event, this.reservations.rosterRow(event.id(), reservationId).orElseThrow(), now);
	}

	private EventReservationResponse response(Occurrence event, RosterRow row, Instant now) {
		List<EventReservationResponse> responses = responses(event, List.of(row), now);
		if (responses.isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		return responses.getFirst();
	}

	/** The rows with their participants, by last and first name; rows without a profile are left out. */
	private List<EventReservationResponse> responses(Occurrence event, List<RosterRow> rows, Instant now) {
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, ProfileDetails> profiles = this.customers
			.findAllById(rows.stream().map(row -> row.reservation().customerId()).distinct().toList()).stream()
			.collect(Collectors.toMap(ProfileDetails::id, Function.identity()));
		Map<UUID, Funding> funding = this.passes.funding(rows.stream()
			.map(row -> new FundingRequest(row.reservation().id(), row.reservation().customerId(),
					row.reservation().customerPassId(), row.makeupCreditId()))
			.toList());
		Map<UUID, AuditLog.Recorded> bookings = staffBookings(rows.stream().map(row -> row.reservation().id()).toList());
		Map<UUID, String> names = staffNames(rows.stream()
			.filter(row -> STAFF_SOURCE.equals(row.source()))
			.map(RosterRow::bookedBy));
		return rows.stream()
			.filter(row -> profiles.containsKey(row.reservation().customerId()))
			.map(row -> response(event, row, profiles.get(row.reservation().customerId()),
					funding.get(row.reservation().id()), bookings.get(row.reservation().id()), names, now))
			.sorted(BY_NAME)
			.toList();
	}

	private EventReservationResponse response(Occurrence event, RosterRow row, ProfileDetails profile,
			Funding funding, AuditLog.Recorded booking, Map<UUID, String> names, Instant now) {
		Reservation reservation = row.reservation();
		boolean staff = STAFF_SOURCE.equals(row.source());
		String unavailable = cancellationReason(reservation, event, now);
		Boolean suggested = suggestedEntryReturned(reservation, now);
		GroupClassDtos.CancellationAvailability participant = GroupClassBookingService.cancellation(reservation, event, now);
		if (reservation.makeupCreditId() != null && !this.makeups.canReturn(profile.id(),
				reservation.makeupCreditId(), reservation.id(), now)) {
			suggested = suggested == null ? null : false;
			participant = new GroupClassDtos.CancellationAvailability(participant.allowed(), participant.reason(),
					participant.entryReturned() == null ? null : false);
		}
		StaffCancellationResponse cancellation = new StaffCancellationResponse(unavailable == null, unavailable,
				isLate(reservation, now), suggested, participant);
		return new EventReservationResponse(reservation.id(), profile.id(), profile.kind(), profile.firstName(),
				profile.lastName(), profile.displayName(), reservation.status(), row.bookedAt(), row.source(),
				staff ? names.get(row.bookedBy()) : null, booking == null ? null : booking.reason(),
				booking == null ? List.of() : overriddenIssues(booking), reservation.customerPassId(),
				funding == null ? null : funding.passName(), cancellation, reservation.cancelledAt(),
				reservation.entryReturned(), reservation.version(), reservation.makeupCreditId());
	}

	/** The audit records of the administrators' bookings of the reservations. */
	private Map<UUID, AuditLog.Recorded> staffBookings(Collection<UUID> reservationIds) {
		Map<UUID, AuditLog.Recorded> bookings = new HashMap<>();
		this.auditLog.history(MODULE, ENTITY_TYPE, reservationIds).stream()
			.filter(entry -> BOOKED.equals(entry.action()))
			.forEach(entry -> bookings.put(entry.entityId(), entry));
		return bookings;
	}

	private static List<BookingIssue> overriddenIssues(AuditLog.Recorded booking) {
		AuditLog.Change change = booking.changes().get("overriddenIssues");
		if (change == null || !(change.newValue() instanceof Collection<?> codes)) {
			return List.of();
		}
		return codes.stream().map(Objects::toString).map(BookingIssue::valueOf).toList();
	}

	/** Names of the staff accounts that booked places; accounts without a staff profile are left out. */
	private Map<UUID, String> staffNames(Stream<UUID> accountIds) {
		Map<UUID, String> names = new HashMap<>();
		accountIds.filter(Objects::nonNull).distinct().forEach(accountId -> this.staffProfiles.findByAccount(accountId)
			.ifPresent(staff -> names.put(accountId, staff.displayName())));
		return names;
	}

	private Occurrence requireEvent(UUID eventId) {
		return this.schedule.findIncludingPrivate(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Group class not found."));
	}

	private ProfileDetails requireParticipant(UUID customerId) {
		return this.customers.findById(customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
	}

	private static String reason(String reason) {
		return reason == null || reason.isBlank() ? null : reason.strip();
	}

	private static ApiException reservationNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Reservation not found.");
	}

	record BookingResult(EventReservationResponse reservation, boolean created) {
	}

}
