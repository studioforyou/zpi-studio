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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.AttendanceDtos.MarkAttendanceRequest;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.OwnEventAttendanceResponse;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Attendance of the participants of the signed-in trainer's events (#102), for the trainers who run
 * or assist them, from the event's start until 12 hours after its end.
 */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/schedule-events/{eventId}")
@Tag(name = "booking")
class OwnEventAttendanceController {

	private static final String NOT_FOUND = "No such event, the trainer does not run or assist it, it is a block, or "
			+ "the account has no staff profile; NOT_FOUND also for a reservation that is not the event's.";

	private static final String CLOSED = "EVENT_CANCELLED; ATTENDANCE_NOT_OPEN before the event starts; "
			+ "ATTENDANCE_CLOSED more than 12 hours after it ends, when only an administrator corrects attendance.";

	private final AttendanceService attendance;

	OwnEventAttendanceController(AttendanceService attendance) {
		this.attendance = attendance;
	}

	@GetMapping("/attendance")
	@Operation(summary = "Get the attendance sheet of my event", operationId = "getOwnEventAttendance",
			description = "Participants with an active reservation and their attendance, sorted by last and first "
					+ "name, with the time in which the trainer can record it.")
	@ApiResponse(responseCode = "200", description = "The attendance sheet.")
	@ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	OwnEventAttendanceResponse get(@PathVariable UUID eventId, AccountAuthentication authentication) {
		return this.attendance.ownSheet(accountId(authentication), eventId);
	}

	@PatchMapping("/attendance")
	@Operation(summary = "Record attendance at my event", operationId = "markOwnEventAttendance",
			description = "Records PRESENT or ABSENT for the given participants, all together. They can be changed "
					+ "again until the trainer's time ends; each change is kept in the attendance history. The first "
					+ "recorded attendance uses the participant's pass entry, whether present or absent.")
	@ApiResponse(responseCode = "200", description = "The updated attendance sheet.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. no marks or one reservation marked "
			+ "twice.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = CLOSED + " RESERVATION_NOT_ACTIVE for a cancelled reservation.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	OwnEventAttendanceResponse mark(@PathVariable UUID eventId, @Valid @RequestBody MarkAttendanceRequest request,
			AccountAuthentication authentication) {
		return this.attendance.mark(accountId(authentication), eventId, request.marks());
	}

	@PostMapping("/complete")
	@Operation(summary = "Mark my event as held", operationId = "completeOwnEvent",
			description = "Records that the event took place: participants without recorded attendance become "
					+ "PRESENT, a personal training session gets the trainer's confirmation, and the event becomes "
					+ "COMPLETED. Attendance can still be changed until the trainer's time ends. Repeating it "
					+ "changes nothing more.")
	@ApiResponse(responseCode = "200", description = "The attendance sheet of the held event.")
	@ApiResponse(responseCode = "404", description = NOT_FOUND, content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = CLOSED, content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	OwnEventAttendanceResponse complete(@PathVariable UUID eventId, AccountAuthentication authentication) {
		return this.attendance.complete(accountId(authentication), eventId);
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
