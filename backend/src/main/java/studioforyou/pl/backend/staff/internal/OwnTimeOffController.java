package studioforyou.pl.backend.staff.internal;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.internal.TimeOffDtos.CreateOwnTimeOffRequest;
import studioforyou.pl.backend.staff.internal.TimeOffDtos.TimeOffResponse;

/**
 * The signed-in trainer's leave and unavailability. The staff member comes from the account,
 * never from the request.
 */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/time-off")
@Tag(name = "staff")
@ApiResponse(responseCode = "404", description = "The account has no staff profile, or the time off does not exist.",
		content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class OwnTimeOffController {

	private final StaffTimeOff timeOff;

	private final StaffProfiles staffProfiles;

	OwnTimeOffController(StaffTimeOff timeOff, StaffProfiles staffProfiles) {
		this.timeOff = timeOff;
		this.staffProfiles = staffProfiles;
	}

	@GetMapping
	@Operation(summary = "List my time off", operationId = "listOwnTimeOff",
			description = "All of the signed-in trainer's requests in every status, the latest start first.")
	@ApiResponse(responseCode = "200", description = "The trainer's time off.")
	ListResponse<TimeOffResponse> listOwnTimeOff(AccountAuthentication authentication) {
		return new ListResponse<>(this.timeOff.findOwn(staffId(authentication)));
	}

	@PostMapping
	@Operation(summary = "Request time off", operationId = "requestOwnTimeOff",
			description = "Creates a REQUESTED entry that waits for an administrator's decision. It already shows "
					+ "as a warning when events are planned in that period. The period must not have ended, "
					+ "lasts at most 366 days and must not overlap the trainer's other requested or approved time off. "
					+ "The reason is trimmed; a blank reason is stored as null.")
	@ApiResponse(responseCode = "201", description = "The created request.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. endsAt not after startsAt.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "TIME_OFF_OVERLAP with conflictingTimeOffId.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<TimeOffResponse> requestOwnTimeOff(AccountAuthentication authentication,
			@Valid @RequestBody CreateOwnTimeOffRequest request) {
		TimeOffResponse created = this.timeOff.request(staffId(authentication), request.kind(), request.startsAt(),
				request.endsAt(), request.reason());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{timeOffId}")
			.buildAndExpand(created.id())
			.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@PatchMapping("/{timeOffId}")
	@Operation(summary = "Change my time-off request", operationId = "updateOwnTimeOff",
			description = "Replaces the kind, period and reason of a request that still waits for a decision. The same "
					+ "rules as for a new request apply; the request itself does not count as an overlap.")
	@ApiResponse(responseCode = "200", description = "The changed request.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. endsAt not after startsAt.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "TIME_OFF_ALREADY_DECIDED with the current status, or "
			+ "TIME_OFF_OVERLAP with conflictingTimeOffId.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TimeOffResponse updateOwnTimeOff(AccountAuthentication authentication, @PathVariable UUID timeOffId,
			@Valid @RequestBody CreateOwnTimeOffRequest request) {
		return this.timeOff.updateOwn(staffId(authentication), timeOffId, request.kind(), request.startsAt(),
				request.endsAt(), request.reason());
	}

	@PostMapping("/{timeOffId}/cancel")
	@Operation(summary = "Cancel my time off", operationId = "cancelOwnTimeOff",
			description = "Withdraws a requested or approved entry that has not ended yet. The entry stays in the "
					+ "history as CANCELLED.")
	@ApiResponse(responseCode = "200", description = "The cancelled time off.")
	@ApiResponse(responseCode = "422", description = "TIME_OFF_NOT_CANCELLABLE: rejected, cancelled or already over.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TimeOffResponse cancelOwnTimeOff(AccountAuthentication authentication, @PathVariable UUID timeOffId) {
		return this.timeOff.cancelOwn(staffId(authentication), timeOffId);
	}

	private UUID staffId(AccountAuthentication authentication) {
		UUID accountId = authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
		return this.staffProfiles.getByAccount(accountId).id();
	}

}
