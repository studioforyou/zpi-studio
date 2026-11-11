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
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.TrainerBookRequest;
import studioforyou.pl.backend.booking.internal.StaffBookingDtos.TrainerBookingOptionsResponse;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Trainers book participants on the group classes they run or assist (#106), e.g. a person who
 * asked them to join, on the rules of the participant's own booking: the booking window, a free
 * place, the class's requirements and an eligible pass. Unlike administrators, trainers cannot
 * override any of them. The class's participants and their attendance are served under the same
 * path.
 */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/schedule-events/{eventId}")
@Tag(name = "booking")
class OwnEventReservationController {

	private static final String PROBLEM = "application/problem+json";

	private static final String NOT_FOUND = "No such group class, the trainer does not run or assist it, the account "
			+ "has no staff profile, or no such participant.";

	private final StaffBookingService bookings;

	OwnEventReservationController(StaffBookingService bookings) {
		this.bookings = bookings;
	}

	@GetMapping("/booking-options")
	@Operation(summary = "Check booking a participant on my group class", operationId = "getOwnEventBookingOptions",
			description = "Whether the trainer can book a place in a group class they run or assist for the "
					+ "participant, on the rules of the participant's own booking, with the first rule broken and the "
					+ "participant's eligible passes.")
	@ApiResponse(responseCode = "200", description = "What booking the participant takes.")
	@ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(mediaType = PROBLEM,
			schema = @Schema(implementation = ApiProblem.class)))
	TrainerBookingOptionsResponse options(@PathVariable UUID eventId, @RequestParam UUID customerId,
			AccountAuthentication authentication) {
		return this.bookings.ownOptions(accountId(authentication), eventId, customerId);
	}

	@PostMapping("/reservations")
	@Operation(summary = "Book a participant on my group class", operationId = "bookOwnEventReservation",
			description = "Books a place for the participant in a group class the trainer runs or assists, on the "
					+ "rules of the participant's own booking: within the booking window, on a free place, meeting "
					+ "the class's requirements and charging one of the participant's eligible passes. The "
					+ "participant can cancel it in the app on the cancellation terms stored now. A participant on "
					+ "the waiting list leaves it. The booking, the trainer who made it and the optional reason are "
					+ "kept in the history. A retry when the participant already has an active reservation returns it "
					+ "without charging another entry.")
	@ApiResponse(responseCode = "201", description = "The place is booked.")
	@ApiResponse(responseCode = "200", description = "The participant's existing active reservation, unchanged.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. no customerPassId or a reason longer "
			+ "than 500 characters.", content = @Content(mediaType = PROBLEM,
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = NOT_FOUND + " NOT_FOUND also for an unknown pass.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "CLASS_FULL: no free place is left.", content = @Content(
			mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "EVENT_NOT_BOOKABLE (cancelled, completed or started), "
			+ "BOOKING_NOT_OPEN, BOOKING_CLOSED, CUSTOMER_NOT_ACTIVE, SERVICE_TYPE_UNAVAILABLE, AGE_RESTRICTION, "
			+ "REQUIRED_DOCUMENTS_MISSING, NO_ELIGIBLE_PASS, or PASS_NOT_USABLE when the chosen pass cannot fund the "
			+ "class.",
			content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<EventReservationResponse> book(@PathVariable UUID eventId,
			@Valid @RequestBody TrainerBookRequest request, AccountAuthentication authentication) {
		StaffBookingService.BookingResult result = this.bookings.ownBook(accountId(authentication), eventId, request);
		if (!result.created()) {
			return ResponseEntity.ok(result.reservation());
		}
		// The place is managed under the administrators' path; the trainer sees it among the participants.
		return ResponseEntity.created(URI.create("/api/v1/schedule-events/" + eventId + "/reservations/"
				+ result.reservation().reservationId())).body(result.reservation());
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
