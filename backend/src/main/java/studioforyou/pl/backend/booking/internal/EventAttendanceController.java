package studioforyou.pl.backend.booking.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.AttendanceDtos.AttendanceRecordResponse;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.CorrectAttendanceRequest;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.EventAttendanceResponse;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Administrators review and correct the attendance of any event's participants (#111), with an
 * optional reason, keeping the history of every change.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/schedule-events/{eventId}")
@Tag(name = "booking")
class EventAttendanceController {

	private final AttendanceService attendance;

	EventAttendanceController(AttendanceService attendance) {
		this.attendance = attendance;
	}

	@GetMapping("/attendance")
	@Operation(summary = "Get the attendance of an event", operationId = "getEventAttendance",
			description = "Participants with an active reservation, sorted by last and first name, with their "
					+ "attendance, who recorded it, and the history of its changes.")
	@ApiResponse(responseCode = "200", description = "The event's attendance.")
	@ApiResponse(responseCode = "404", description = "No such event, or it is a block.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	EventAttendanceResponse get(@PathVariable UUID eventId) {
		return this.attendance.sheet(eventId);
	}

	@PostMapping("/reservations/{reservationId}/correct-attendance")
	@Operation(summary = "Correct a participant's attendance", operationId = "correctEventAttendance",
			description = "Sets PRESENT or ABSENT at any time after the event's start, also after the trainers' time "
					+ "or on a held event. The previous value, the administrator, the time and the optional reason are "
					+ "added to the history; nothing is overwritten. The pass entry is used by the first recorded "
					+ "attendance either way, so a change between PRESENT and ABSENT does not change the pass; correct "
					+ "the pass's entries separately if needed. Setting the current value changes nothing.")
	@ApiResponse(responseCode = "200", description = "The participant's attendance with its history.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. a reason longer than 500 characters.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such event or reservation of the event, or the event is a "
			+ "block.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT: reload the attendance before retrying.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "EVENT_CANCELLED; ATTENDANCE_NOT_OPEN before the event starts; "
			+ "RESERVATION_NOT_ACTIVE for a cancelled reservation.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	AttendanceRecordResponse correct(@PathVariable UUID eventId, @PathVariable UUID reservationId,
			@Valid @RequestBody CorrectAttendanceRequest request, AccountAuthentication authentication) {
		UUID actorId = authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
		return this.attendance.correct(actorId, eventId, reservationId, request);
	}

}
