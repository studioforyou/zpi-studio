package studioforyou.pl.backend.booking.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.internal.GroupClassDtos.BookingAvailability;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancelRequest;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancellationAvailability;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.EligiblePassResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.GroupClassResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.InstructorResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ReservationResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.RoomResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ParticipantResponse;
import studioforyou.pl.backend.booking.internal.ReservationStore.Reservation;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.documents.DocumentRequirements;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * Coordinates self-service participation in one transaction. Lock order is occurrence,
 * reservation, then pass; each participating module accesses only its own persistence.
 */
@Service
@Transactional
class GroupClassBookingService {

	private static final Duration MAX_WINDOW = Duration.ofDays(93);

	private final ReservationStore reservations;

	private final GroupClassSchedule schedule;

	private final CustomerProfiles customers;

	private final ServiceTypes services;

	private final BookingPolicies policies;

	private final BookingEntitlements entitlements;

	private final DocumentRequirements documents;

	private final StudioSettings studio;

	private final Clock clock = Clock.systemUTC();

	GroupClassBookingService(ReservationStore reservations, GroupClassSchedule schedule,
			CustomerProfiles customers, ServiceTypes services, BookingPolicies policies,
			BookingEntitlements entitlements, DocumentRequirements documents, StudioSettings studio) {
		this.reservations = reservations;
		this.schedule = schedule;
		this.customers = customers;
		this.services = services;
		this.policies = policies;
		this.entitlements = entitlements;
		this.documents = documents;
		this.studio = studio;
	}

	@Transactional(readOnly = true)
	List<GroupClassResponse> list(UUID actorId, UUID profileId, Instant from, Instant to, boolean onlyOwn) {
		ProfileDetails profile = requireProfile(actorId, profileId);
		requireWindow(from, to);
		Instant now = this.clock.instant();
		List<Occurrence> occurrences = this.schedule.list(from, to).stream()
			.filter(event -> !onlyOwn || event.startsAt().isAfter(now)).toList();
		List<UUID> ids = occurrences.stream().map(Occurrence::id).toList();
		Map<UUID, Reservation> own = this.reservations.ownActive(profileId, ids);
		Map<UUID, Integer> counts = this.reservations.activeCounts(ids);
		return occurrences.stream().filter(event -> !onlyOwn || own.containsKey(event.id()))
			.map(event -> project(profile, event, own.get(event.id()), counts.getOrDefault(event.id(), 0), now))
			.toList();
	}

	@Transactional(readOnly = true)
	GroupClassResponse get(UUID actorId, UUID profileId, UUID eventId) {
		ProfileDetails profile = requireProfile(actorId, profileId);
		Occurrence event = requireEvent(eventId);
		Reservation own = this.reservations.ownActive(profileId, List.of(eventId)).get(eventId);
		return project(profile, event, own, this.reservations.activeCount(eventId), this.clock.instant());
	}

	@Transactional(readOnly = true)
	ReservationResponse getReservation(UUID actorId, UUID profileId, UUID reservationId) {
		requireProfile(actorId, profileId);
		Reservation reservation = requireReservation(profileId, reservationId);
		return reservationResponse(reservation, requireEvent(reservation.eventId()), this.clock.instant());
	}

	@Transactional(readOnly = true)
	List<ParticipantResponse> participants(UUID actorId, UUID profileId, UUID eventId) {
		requireProfile(actorId, profileId);
		requireEvent(eventId);
		if (!this.reservations.ownActive(profileId, List.of(eventId)).containsKey(eventId)) {
			throw new ApiException(ErrorCode.ACCESS_DENIED,
					"The participant list is available to participants with an active reservation.");
		}
		List<UUID> ids = this.reservations.activeParticipantIds(eventId);
		Map<UUID, String> names = this.customers.displayNames(ids);
		return ids.stream().filter(names::containsKey)
			.map(id -> new ParticipantResponse(names.get(id), id.equals(profileId)))
			.sorted(Comparator.comparing(ParticipantResponse::displayName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(ParticipantResponse::isSelf, Comparator.reverseOrder()))
			.toList();
	}

	BookingResult book(UUID actorId, UUID profileId, UUID eventId, UUID customerPassId) {
		ProfileDetails profile = requireProfile(actorId, profileId);
		Occurrence event = this.schedule.lock(eventId);
		Instant now = this.clock.instant();
		Reservation existing = this.reservations.lockActive(profileId, eventId).orElse(null);
		if (existing != null) {
			return new BookingResult(reservationResponse(existing, event, now), false);
		}
		BookingPolicies.Definition policy = this.policies.forOccurrence(event.serviceTypeId(),
				event.trainingGroupId(), event.id());
		LocalDate eventDate = LocalDate.ofInstant(event.startsAt(), this.studio.zone());
		BookingAvailability availability = availability(profile, event, null,
				this.reservations.activeCount(eventId), eventDate, policy.effective(), now);
		if (!availability.allowed()) {
			throw rejected(availability.reason());
		}
		BookingPolicies.Values rules = policy.effective();
		UUID reservationId = UUID.randomUUID();
		boolean ownProfile = this.customers.findOwnProfile(actorId)
			.map(own -> own.id().equals(profileId)).orElse(false);
		this.reservations.insert(reservationId, eventId, profileId, actorId,
				ownProfile ? "SELF" : "GUARDIAN", policy.policyId(),
				event.startsAt().minus(Duration.ofHours(rules.freeCancelHours())),
				rules.cancellationAllowed(), rules.lateCancelAllowed(), rules.lateCancelForfeitsEntry());
		// Insert before the ledger FK, but attach funding only after its owner's validation/lock.
		// Otherwise concurrent inserts can both take pass FK KEY SHARE locks before upgrading them.
		this.entitlements.reserve(profileId, customerPassId, event.serviceTypeId(), eventDate, reservationId, actorId);
		Reservation created = this.reservations.attachPass(reservationId, customerPassId);
		return new BookingResult(reservationResponse(created, event, now), true);
	}

	ReservationResponse cancel(UUID actorId, UUID profileId, UUID reservationId, CancelRequest request) {
		requireProfile(actorId, profileId);
		// Read only the occurrence ID first, then take the agreed locks in owner order.
		Reservation reference = requireReservation(profileId, reservationId);
		Occurrence event = this.schedule.lock(reference.eventId());
		Reservation reservation = this.reservations.lock(profileId, reservationId)
			.orElseThrow(GroupClassBookingService::notFoundReservation);
		Instant now = this.clock.instant();
		if ("CANCELLED".equals(reservation.status())) {
			return reservationResponse(reservation, event, now);
		}
		if (reservation.version() != request.expectedVersion()) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT, "The reservation has changed. Refresh it before cancelling.");
		}
		CancellationAvailability consequence = cancellation(reservation, event, now);
		if (!consequence.allowed()) {
			throw rejected(consequence.reason());
		}
		if (!request.expectedEntryReturned().equals(consequence.entryReturned())) {
			throw new ApiException(ErrorCode.CANCELLATION_CONSEQUENCE_CHANGED,
					"The cancellation consequence has changed. Refresh the class and confirm it again.",
					Map.of("entryReturned", consequence.entryReturned()));
		}
		this.entitlements.release(profileId, reservation.customerPassId(), reservation.id(),
				consequence.entryReturned(), actorId);
		String kind = now.isAfter(reservation.freeCancelUntil()) ? "LATE" : "IN_TIME";
		Reservation cancelled = this.reservations.cancel(reservation, actorId, now, consequence.entryReturned(), kind);
		return reservationResponse(cancelled, event, now);
	}

	private GroupClassResponse project(ProfileDetails profile, Occurrence event, Reservation own,
			int activeCount, Instant now) {
		LocalDate date = LocalDate.ofInstant(event.startsAt(), this.studio.zone());
		BookingPolicies.Values policy = this.policies.forOccurrence(event.serviceTypeId(),
				event.trainingGroupId(), event.id()).effective();
		Integer availablePlaces = event.capacity() == null ? null : Math.max(0, event.capacity() - activeCount);
		return new GroupClassResponse(event.id(), event.title(), event.serviceTypeId(), event.serviceTypeName(),
				event.calendarColor(), event.startsAt(), event.endsAt(), event.status(), event.capacity(), activeCount, availablePlaces,
				event.staff().stream().map(staff -> new InstructorResponse(staff.staffId(), staff.displayName(), staff.role())).toList(),
				event.resources().stream().map(room -> new RoomResponse(room.resourceId(), room.name())).toList(),
				own == null ? null : reservationResponse(own, event, now),
				availability(profile, event, own, activeCount, date, policy, now), event.version());
	}

	private BookingAvailability availability(ProfileDetails profile, Occurrence event, Reservation own,
			int count, LocalDate date, BookingPolicies.Values rules, Instant now) {
		List<EligiblePassResponse> passes = this.entitlements.eligible(profile.id(), event.serviceTypeId(), date)
			.stream().map(pass -> new EligiblePassResponse(pass.id(), pass.productName(), pass.entriesRemaining(), pass.validUntil())).toList();
		String reason = null;
		if (own != null) {
			reason = "ALREADY_RESERVED";
		}
		else if (!"SCHEDULED".equals(event.status()) || !event.startsAt().isAfter(now)) {
			reason = "EVENT_NOT_BOOKABLE";
		}
		else if (now.isBefore(event.startsAt().minus(Duration.ofDays(rules.bookingOpensDaysBefore())))) {
			reason = "BOOKING_NOT_OPEN";
		}
		else if (!now.isBefore(event.startsAt().minus(Duration.ofMinutes(rules.bookingClosesMinutesBefore())))) {
			reason = "BOOKING_CLOSED";
		}
		else if (event.capacity() != null && count >= event.capacity()) {
			reason = "CLASS_FULL";
		}
		else {
			ServiceTypes.Definition service = this.services.findDefinitionById(event.serviceTypeId()).orElse(null);
			if (service == null || !service.active()) {
				reason = "SERVICE_TYPE_UNAVAILABLE";
			}
			else if (!ageAllowed(profile.birthDate(), date, service.minAge(), service.maxAge())) {
				reason = "AGE_RESTRICTION";
			}
			else if (this.documents.hasMissing(profile.id(), event.serviceTypeId(), profile.kind().name())) {
				reason = "REQUIRED_DOCUMENTS_MISSING";
			}
			else if (passes.isEmpty()) {
				reason = "NO_ELIGIBLE_PASS";
			}
		}
		return new BookingAvailability(reason == null, reason, passes);
	}

	static boolean ageAllowed(LocalDate birthDate, LocalDate occurrenceDate, Short minAge, Short maxAge) {
		if (minAge == null && maxAge == null) {
			return true;
		}
		if (birthDate == null || birthDate.isAfter(occurrenceDate)) {
			return false;
		}
		int age = Period.between(birthDate, occurrenceDate).getYears();
		return (minAge == null || age >= minAge) && (maxAge == null || age <= maxAge);
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
				|| reservation.lateCancelForfeitsEntry() == null || reservation.freeCancelUntil() == null
				|| reservation.customerPassId() == null) {
			return unavailableCancellation("CANCELLATION_TERMS_UNAVAILABLE");
		}
		if (!reservation.cancellationAllowed()) {
			return unavailableCancellation("CANCELLATION_NOT_ALLOWED");
		}
		boolean late = now.isAfter(reservation.freeCancelUntil());
		if (late && !reservation.lateCancelAllowed()) {
			return unavailableCancellation("FREE_CANCELLATION_EXPIRED");
		}
		return new CancellationAvailability(true, null, !late || !reservation.lateCancelForfeitsEntry());
	}

	private static CancellationAvailability unavailableCancellation(String reason) {
		return new CancellationAvailability(false, reason, null);
	}

	private static ReservationResponse reservationResponse(Reservation reservation, Occurrence event, Instant now) {
		return new ReservationResponse(reservation.id(), reservation.eventId(), reservation.customerId(),
				reservation.status(), reservation.customerPassId(), reservation.freeCancelUntil(), reservation.cancelledAt(),
				reservation.cancelKind(), reservation.entryReturned(), cancellation(reservation, event, now), reservation.version());
	}

	private ProfileDetails requireProfile(UUID actorId, UUID profileId) {
		if (!this.customers.isActingFor(actorId, profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		return this.customers.findById(profileId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
	}

	private Occurrence requireEvent(UUID eventId) {
		return this.schedule.find(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Group class not found."));
	}

	private Reservation requireReservation(UUID profileId, UUID reservationId) {
		return this.reservations.find(profileId, reservationId)
			.orElseThrow(GroupClassBookingService::notFoundReservation);
	}

	private static ApiException notFoundReservation() {
		return new ApiException(ErrorCode.NOT_FOUND, "Reservation not found.");
	}

	private static ApiException rejected(String reason) {
		return new ApiException(ErrorCode.valueOf(reason), "The requested operation is unavailable: " + reason + ".");
	}

	private static void requireWindow(Instant from, Instant to) {
		if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE,
					"The time window must be positive and no longer than 93 days.");
		}
	}

	record BookingResult(ReservationResponse reservation, boolean created) {
	}

}
