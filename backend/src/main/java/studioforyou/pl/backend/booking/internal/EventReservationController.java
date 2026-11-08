package studioforyou.pl.backend.booking.internal;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.StaffBookingDtos.EventReservationResponse;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.EventReservationsResponse;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.StaffBookRequest;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.StaffBookingOptionsResponse;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.StaffCancelRequest;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Administrators book and cancel group-class places on behalf of participants (#110), e.g. after
 * a phone call or at the desk.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/schedule-events/{eventId}")
@Tag(name = "booking")
class EventReservationController {

	private static final String PROBLEM = "application/problem+json";

	private final StaffBookingService bookings;

	EventReservationController(StaffBookingService bookings) {
		this.bookings = bookings;
	}

	@GetMapping("/reservations")
	@Operation(summary = "List the places booked in a group class", operationId = "listEventReservations",
			description = "Participants with an active reservation for a public or private group class, sorted by last "
					+ "and first name, with who booked the place, its pass and the cancellation terms.")
	@ApiResponse(responseCode = "200", description = "The class's places.")
	@ApiResponse(responseCode = "404", description = "No such group class.", content = @Content(mediaType = PROBLEM,
			schema = @Schema(implementation = ApiProblem.class)))
	EventReservationsResponse list(@PathVariable UUID eventId) {
		return this.bookings.roster(eventId);
	}

	@GetMapping("/reservations/{reservationId}")
	@Operation(summary = "Get a place booked in a group class", operationId = "getEventReservation")
	@ApiResponse(responseCode = "404", description = "No such group class or reservation of the class.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	EventReservationResponse get(@PathVariable UUID eventId, @PathVariable UUID reservationId) {
		return this.bookings.reservation(eventId, reservationId);
	}

	@GetMapping("/booking-options")
	@Operation(summary = "Check booking a participant on a group class", operationId = "getStaffBookingOptions",
			description = "Whether an administrator can book a place for the participant, the participant's eligible "
					+ "passes, and the issues to confirm: a full class and the requirements the participant does not "
					+ "meet. The booking window does not bind administrators.")
	@ApiResponse(responseCode = "404", description = "No such group class or participant.", content = @Content(
			mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	StaffBookingOptionsResponse options(@PathVariable UUID eventId, @RequestParam UUID customerId) {
		return this.bookings.options(eventId, customerId);
	}

	@PostMapping("/reservations")
	@Operation(summary = "Book a participant on a group class", operationId = "bookEventReservation",
			description = "Books a place for the participant until the class starts, whatever the booking window. "
					+ "A full class and each requirement the participant does not meet have to be confirmed in "
					+ "acceptedIssues. The chosen pass is charged like a participant's own booking; without an "
					+ "eligible pass the place is booked without a pass entry. The participant can cancel it in the "
					+ "app on the cancellation terms stored now. A participant on the waiting list leaves it. The "
					+ "booking and its optional reason are kept in the history. A retry when the participant already "
					+ "has an active reservation returns it without charging another entry.")
	@ApiResponse(responseCode = "201", description = "The place is booked; Location identifies the reservation.")
	@ApiResponse(responseCode = "200", description = "The participant's existing active reservation, unchanged.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. no customerPassId although the "
			+ "participant has an eligible pass, or a reason longer than 500 characters.", content = @Content(
					mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such group class, participant or pass.", content = @Content(
			mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "BOOKING_OVERRIDE_REQUIRED: issues that were not confirmed, "
			+ "listed in the issues property.", content = @Content(mediaType = PROBLEM,
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "EVENT_NOT_BOOKABLE (cancelled, completed or started), "
			+ "CUSTOMER_NOT_ACTIVE, or PASS_NOT_USABLE and the pass errors when the chosen pass cannot fund the class.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<EventReservationResponse> book(@PathVariable UUID eventId,
			@Valid @RequestBody StaffBookRequest request, AccountAuthentication authentication) {
		StaffBookingService.BookingResult result = this.bookings.book(actor(authentication), eventId, request);
		if (!result.created()) {
			return ResponseEntity.ok(result.reservation());
		}
		return ResponseEntity.created(URI.create("/api/v1/schedule-events/" + eventId + "/reservations/"
				+ result.reservation().reservationId())).body(result.reservation());
	}

	@PostMapping("/reservations/{reservationId}/cancel")
	@Operation(summary = "Cancel a participant's place in a group class", operationId = "cancelEventReservation",
			description = "Cancels the place until the class starts, also when the participant could not cancel it "
					+ "any more. The administrator decides whether the pass entry is returned; "
					+ "cancellation.suggestedEntryReturned is what the stored terms give. In the same transaction the "
					+ "freed place goes to the first participants on the waiting list who meet the booking rules. The "
					+ "cancellation and its optional reason are kept in the history. Repeating it returns the "
					+ "cancelled reservation unchanged.")
	@ApiResponse(responseCode = "200", description = "The cancelled reservation.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. no returnEntry for a reservation "
			+ "with a pass.", content = @Content(mediaType = PROBLEM,
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such group class or reservation of the class.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT: reload the class's places before retrying; "
			+ "also when a waiting participant's pass changed while the freed place was given to them.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "EVENT_ALREADY_STARTED, or EVENT_NOT_BOOKABLE for a cancelled "
			+ "or completed class.", content = @Content(mediaType = PROBLEM,
					schema = @Schema(implementation = ApiProblem.class)))
	EventReservationResponse cancel(@PathVariable UUID eventId, @PathVariable UUID reservationId,
			@Valid @RequestBody StaffCancelRequest request, AccountAuthentication authentication) {
		return this.bookings.cancel(actor(authentication), eventId, reservationId, request);
	}

	private static UUID actor(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
