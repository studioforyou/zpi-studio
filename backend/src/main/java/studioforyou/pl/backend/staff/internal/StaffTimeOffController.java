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
import studioforyou.pl.backend.staff.TimeOffSubstitutions;
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
	private final TimeOffSubstitutions substitutions;

	StaffTimeOffController(StaffTimeOff timeOff, TimeOffSubstitutions substitutions) {
		this.timeOff = timeOff;
		this.substitutions = substitutions;
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
				request.startsAt(), request.endsAt(), request.reason(), request.substitutionEventIds());
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
		return this.timeOff.approve(timeOffId, accountId(authentication), note(request),
				request == null ? null : request.version(), request == null ? null : request.substitutionEventIds());
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
		return this.timeOff.reject(timeOffId, accountId(authentication), note(request), request == null ? null : request.version());
	}

	@PostMapping("/{timeOffId}/cancel")
	@Operation(summary = "Cancel time off", operationId = "cancelStaffTimeOff",
			description = "Cancels requested or approved time off that has not ended, e.g. after an early return.")
	@ApiResponse(responseCode = "200", description = "The cancelled time off.")
	@ApiResponse(responseCode = "422", description = "TIME_OFF_NOT_CANCELLABLE: rejected, cancelled or already over.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TimeOffResponse cancelStaffTimeOff(AccountAuthentication authentication, @PathVariable UUID timeOffId) {
		return this.timeOff.cancel(timeOffId, accountId(authentication));
	}

	@GetMapping("/substitution-options")
	@Operation(summary = "Preview sessions covered by a time-off period", operationId = "previewStaffTimeOffSubstitutions")
	TimeOffSubstitutions.Plan preview(@RequestParam UUID staffId, @RequestParam Instant from, @RequestParam Instant to) {
		return this.substitutions.preview(staffId, from, to, null);
	}

	@GetMapping("/{timeOffId}/substitutions")
	@Operation(summary = "Read the cover plan of a time-off entry", operationId = "getStaffTimeOffSubstitutions")
	TimeOffSubstitutions.Plan substitutions(@PathVariable UUID timeOffId) {
		return this.substitutions.plan(timeOffId, null);
	}

	@PostMapping("/{timeOffId}/substitutions")
	@Operation(summary = "Choose sessions and open their substitution offers together", operationId = "selectTimeOffSubstitutions")
	TimeOffSubstitutions.Plan select(AccountAuthentication auth, @PathVariable UUID timeOffId,
			@Valid @RequestBody TimeOffSubstitutions.Selection input) {
		return this.timeOff.selectSubstitutions(timeOffId, input, accountId(auth));
	}

	@PostMapping("/{timeOffId}/substitutions/candidates")
	@Operation(summary = "Check a trainer against every selected session", operationId = "listTimeOffSubstitutionCandidates")
	ListResponse<TimeOffSubstitutions.Candidate> candidates(@PathVariable UUID timeOffId,
			@Valid @RequestBody TimeOffSubstitutions.Selection input) {
		return new ListResponse<>(this.substitutions.candidates(timeOffId, input.eventIds()));
	}

	@PostMapping("/{timeOffId}/substitutions/approve")
	@Operation(summary = "Approve selected substitution claims atomically", operationId = "approveTimeOffSubstitutions")
	TimeOffSubstitutions.Plan approveSubstitutions(AccountAuthentication auth, @PathVariable UUID timeOffId,
			@Valid @RequestBody TimeOffSubstitutions.Batch input) {
		return this.substitutions.decide(timeOffId, input, accountId(auth), true);
	}

	@PostMapping("/{timeOffId}/substitutions/assign")
	@Operation(summary = "Assign one checked trainer to selected sessions atomically", operationId = "assignTimeOffSubstitutions")
	TimeOffSubstitutions.Plan assignSubstitutions(AccountAuthentication auth, @PathVariable UUID timeOffId,
			@Valid @RequestBody TimeOffSubstitutions.Batch input) {
		return this.substitutions.decide(timeOffId, input, accountId(auth), false);
	}

	private static @Nullable String note(@Nullable DecisionRequest request) {
		return request == null ? null : request.note();
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
