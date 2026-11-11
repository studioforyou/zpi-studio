package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancellationAvailability;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.EligiblePassResponse;
import studioforyou.pl.backend.customer.CustomerKind;

/**
 * Group-class places administrators book and cancel for participants (#110), with what they need
 * to decide: the class's places, the participant's passes and the requirements they would override.
 */
final class StaffBookingDtos {

	private static final String SOURCE = "SELF (the participant), GUARDIAN (a guardian for a child), STAFF (an "
			+ "administrator), WAITLIST (a freed place given from the waiting list) or GROUP_AUTO.";

	private StaffBookingDtos() {
	}

	/** What an administrator may override when booking, once they confirm it. */
	enum BookingIssue {

		/** No free place is left; the booking exceeds the class's capacity. */
		CLASS_FULL,
		/** The class's service type is no longer offered. */
		SERVICE_TYPE_UNAVAILABLE,
		/** The participant's age, or an unknown date of birth, is outside the service's limits. */
		AGE_RESTRICTION,
		/** The participant has not accepted a document the service requires. */
		REQUIRED_DOCUMENTS_MISSING,
		/** No pass of the participant can fund the class; the place is booked without a pass entry. */
		NO_ELIGIBLE_PASS

	}

	@Schema(name = "EventReservations", description = "The participants of a group class with what an administrator "
			+ "needs to book and cancel their places.")
	record EventReservationsResponse(UUID eventId,
			@Schema(description = "SCHEDULED, COMPLETED or CANCELLED.") String eventStatus, Instant startsAt,
			@Schema(description = "null means that the class has no capacity limit.") Integer capacity,
			int activeReservations,
			@Schema(description = "Places held for waiting participants to confirm.") int heldOffers,
			@Schema(description = "Free places, never below 0; null without a capacity limit.") Integer availablePlaces,
			@Schema(description = "Participants on the waiting list.") int waitingCount,
			@Schema(description = "Whether an administrator can book and cancel places now: the class is scheduled and "
					+ "has not started.") boolean bookable,
			@Schema(description = "EVENT_NOT_BOOKABLE when the class is cancelled, completed or has started; null "
					+ "otherwise.") String bookableReason,
			@Schema(description = "Why participants cannot book themselves now (BOOKING_NOT_OPEN or BOOKING_CLOSED); an "
					+ "administrator still can until the start. null when they can or the class is not bookable.")
			String clientBookingReason,
			@Schema(description = "Participants with an active reservation, sorted by last and first name.")
			List<EventReservationResponse> participants) {
	}

	@Schema(name = "EventReservation", description = "A participant's place in a group class, without contact details.")
	record EventReservationResponse(UUID reservationId, UUID customerId, CustomerKind kind,
			@Schema(example = "Katarzyna") String firstName, @Schema(example = "Zielińska") String lastName,
			@Schema(example = "Katarzyna Zielińska") String displayName,
			@Schema(description = "ACTIVE or CANCELLED.") String status, Instant bookedAt,
			@Schema(description = SOURCE, example = "STAFF") String source,
			@Schema(description = "The administrator who booked the place for a STAFF booking, or null when unknown.",
					example = "Beata Wrona") String bookedBy,
			@Schema(description = "The administrator's reason for a STAFF booking, if given.",
					example = "Booked on the phone") String bookingReason,
			@Schema(description = "Requirements the administrator overrode when booking.")
			List<BookingIssue> overriddenIssues,
			@Schema(description = "The pass the place is funded from; null when booked without a pass entry.")
			UUID customerPassId,
			@Schema(description = "The funding pass's product name, or null.", example = "Karnet 8 wejść")
			String passName,
			StaffCancellationResponse cancellation,
			Instant cancelledAt,
			@Schema(description = "Whether the cancellation returned the pass entry; null while active or without a pass.")
			Boolean entryReturned,
			@Schema(description = "The reservation's version, sent back with a cancellation.") int version,
			@Schema(description = "The make-up credit funding the place, or null.") UUID makeupCreditId) {
	}

	@Schema(name = "StaffCancellation", description = "Whether an administrator can cancel the place now and what "
			+ "the cancellation terms suggest for the pass entry.")
	record StaffCancellationResponse(
			@Schema(description = "Whether an administrator can cancel the place now: it is active and the class is "
					+ "scheduled and has not started.") boolean allowed,
			@Schema(description = "RESERVATION_NOT_ACTIVE, EVENT_ALREADY_STARTED or EVENT_NOT_BOOKABLE; null when "
					+ "allowed.") String reason,
			@Schema(description = "Whether the free cancellation deadline stored at booking has passed.") boolean late,
			@Schema(description = "Whether the cancellation terms stored at booking return the entry now; the "
					+ "administrator decides. null without a pass or without stored terms.")
			Boolean suggestedEntryReturned,
			@Schema(description = "What the participant could do in the app now.") CancellationAvailability participant) {
	}

	@Schema(name = "StaffBookingOptions", description = "Whether an administrator can book a place in the class for "
			+ "the participant, and what they would have to confirm.")
	record StaffBookingOptionsResponse(UUID eventId, UUID customerId,
			@Schema(description = "Whether a place can be booked, possibly after confirming the issues.") boolean allowed,
			@Schema(description = "Why not: EVENT_NOT_BOOKABLE, ALREADY_RESERVED or CUSTOMER_NOT_ACTIVE; null when "
					+ "allowed.") String reason,
			@Schema(description = "Requirements the administrator has to confirm overriding, in the order checked.")
			List<BookingIssue> issues,
			@Schema(description = "BOOKING_NOT_OPEN or BOOKING_CLOSED when participants cannot book themselves now; "
					+ "the administrator is not bound by the booking window.") String clientBookingReason,
			@Schema(description = "The participant's active reservation for the class, or null.") UUID reservationId,
			@Schema(description = "WAITING or OFFERED when the participant is on the waiting list; booking takes them "
					+ "off it. null otherwise.") String waitlistStatus,
			@Schema(description = "The participant's passes that can fund the class.")
			List<EligiblePassResponse> eligiblePasses) {
	}

	@Schema(name = "StaffBookRequest")
	record StaffBookRequest(@NotNull UUID customerId,
			@Schema(description = "One of the participant's eligible passes; required when they have one. null books "
					+ "the place without a pass entry, which needs NO_ELIGIBLE_PASS confirmed.")
			UUID customerPassId,
			@Schema(description = "The issues the administrator confirmed overriding. Any other current issue rejects "
					+ "the booking with 409 BOOKING_OVERRIDE_REQUIRED.")
			Set<BookingIssue> acceptedIssues,
			@Schema(description = "Optional reason, kept in the history.", example = "Booked on the phone")
			@Size(max = 500) String reason) {
	}

	@Schema(name = "StaffCancelReservationRequest")
	record StaffCancelRequest(
			@Schema(description = "The reservation's current version; a stale value returns 409 VERSION_CONFLICT.")
			@NotNull @Min(0) Integer expectedVersion,
			@Schema(description = "Whether the pass entry goes back to the pass; required for a reservation with a pass, "
					+ "ignored without one.") Boolean returnEntry,
			@Schema(description = "Optional reason, kept in the history.", example = "The participant is ill")
			@Size(max = 500) String reason) {
	}

}
