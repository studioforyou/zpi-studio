package studioforyou.pl.backend.staff.internal;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.internal.TimeOffDtos.CreateStaffTimeOffRequest;
import studioforyou.pl.backend.staff.internal.TimeOffDtos.DecisionRequest;
import studioforyou.pl.backend.staff.internal.TimeOffDtos.TimeOffResponse;

/** Administrators review, record and cancel the time off of all staff members. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/staff/time-off")
@Tag(name = "staff")
@ApiResponse(responseCode = "404", description = "The time off does not exist.", content = @Content(
		mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class StaffTimeOffController {

	private final StaffTimeOff timeOff;

	StaffTimeOffController(StaffTimeOff timeOff) {
		this.timeOff = timeOff;
	}

	@GetMapping
	@Operation(summary = "List staff time off", operationId = "listStaffTimeOff",
			description = "Time off matching all given filters, earliest start first.")
	@ApiResponse(responseCode = "200", description = "The matching time off.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when to is not after from.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ListResponse<TimeOffResponse> listStaffTimeOff(
			@Parameter(description = "Only these statuses; all when missing.")
			@RequestParam(required = false) @Nullable List<TimeOffStatus> status,
			@Parameter(description = "Only this staff member's time off.")
			@RequestParam(required = false) @Nullable UUID staffId,
			@Parameter(description = "Only time off that ends after this moment.", example = "2026-11-01T00:00:00Z")
			@RequestParam(required = false) @Nullable Instant from,
			@Parameter(description = "Only time off that starts before this moment.", example = "2026-12-01T00:00:00Z")
			@RequestParam(required = false) @Nullable Instant to) {
		return new ListResponse<>(this.timeOff.find(status == null ? List.of() : status, staffId, from, to));
	}

	@PostMapping
	@Operation(summary = "Record time off for a staff member", operationId = "recordStaffTimeOff",
			description = "Creates APPROVED time off at once, e.g. sick leave reported by phone. It may lie in the past, "
					+ "lasts at most 366 days and must not overlap the staff member's requested or approved time off.")
	@ApiResponse(responseCode = "201", description = "The recorded time off.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also for an unknown staffId.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "TIME_OFF_OVERLAP with conflictingTimeOffId.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<TimeOffResponse> recordStaffTimeOff(AccountAuthentication authentication,
			@Valid @RequestBody CreateStaffTimeOffRequest request) {
		TimeOffResponse created = this.timeOff.record(accountId(authentication), request.staffId(), request.kind(),
				request.startsAt(), request.endsAt(), request.reason());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{timeOffId}")
			.buildAndExpand(created.id())
			.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@PostMapping("/{timeOffId}/approve")
	@Operation(summary = "Approve a time-off request", operationId = "approveStaffTimeOff",
			description = "Only REQUESTED time off can be approved. The note is optional and shown to the staff member.")
	@ApiResponse(responseCode = "200", description = "The approved time off.")
	@ApiResponse(responseCode = "409", description = "TIME_OFF_ALREADY_DECIDED with the current status.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TimeOffResponse approveStaffTimeOff(AccountAuthentication authentication, @PathVariable UUID timeOffId,
			@Valid @RequestBody(required = false) @Nullable DecisionRequest request) {
		return this.timeOff.approve(timeOffId, accountId(authentication), note(request));
	}

	@PostMapping("/{timeOffId}/reject")
	@Operation(summary = "Reject a time-off request", operationId = "rejectStaffTimeOff",
			description = "Only REQUESTED time off can be rejected. The note is optional and shown to the staff member.")
	@ApiResponse(responseCode = "200", description = "The rejected time off.")
	@ApiResponse(responseCode = "409", description = "TIME_OFF_ALREADY_DECIDED with the current status.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TimeOffResponse rejectStaffTimeOff(AccountAuthentication authentication, @PathVariable UUID timeOffId,
			@Valid @RequestBody(required = false) @Nullable DecisionRequest request) {
		return this.timeOff.reject(timeOffId, accountId(authentication), note(request));
	}

	@PostMapping("/{timeOffId}/cancel")
	@Operation(summary = "Cancel time off", operationId = "cancelStaffTimeOff",
			description = "Cancels requested or approved time off that has not ended, e.g. after an early return.")
	@ApiResponse(responseCode = "200", description = "The cancelled time off.")
	@ApiResponse(responseCode = "422", description = "TIME_OFF_NOT_CANCELLABLE: rejected, cancelled or already over.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TimeOffResponse cancelStaffTimeOff(@PathVariable UUID timeOffId) {
		return this.timeOff.cancel(timeOffId);
	}

	private static @Nullable String note(@Nullable DecisionRequest request) {
		return request == null ? null : request.note();
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
