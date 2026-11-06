package studioforyou.pl.backend.staff.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.AvailabilitySlotResponse;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.CreateAvailabilityRequest;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.UpdateAvailabilityRequest;

/**
 * The signed-in trainer's own working hours. The staff member comes from the account, never
 * from the request.
 */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/availability")
@Tag(name = "staff")
@ApiResponse(responseCode = "404", description = "The account has no staff profile, or the slot does not exist.",
		content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class OwnAvailabilityController {

	private static final String EDIT_OWN = "hasRole('TRAINER') and hasAuthority('AVAILABILITY_EDIT_OWN')";

	private final AvailabilitySlots slots;

	private final StaffProfiles staffProfiles;

	OwnAvailabilityController(AvailabilitySlots slots, StaffProfiles staffProfiles) {
		this.slots = slots;
		this.staffProfiles = staffProfiles;
	}

	@GetMapping
	@Operation(summary = "List my availability", operationId = "listMyAvailability",
			description = "Weekly hours and one-off days that apply today or later: weekly hours by weekday, "
					+ "then one-off days by date. Times are in the studio's local time.")
	@ApiResponse(responseCode = "200", description = "The trainer's availability.")
	ListResponse<AvailabilitySlotResponse> list(AccountAuthentication authentication) {
		return new ListResponse<>(this.slots.list(staffId(authentication)));
	}

	@PostMapping
	@PreAuthorize(EDIT_OWN)
	@Operation(summary = "Add my availability", operationId = "addMyAvailability",
			description = "Adds the same hours on several weekdays (weekly) or single dates, all or nothing. "
					+ "Needs the AVAILABILITY_EDIT_OWN permission.")
	@ApiResponse(responseCode = "201", description = "The added slots.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "AVAILABILITY_OVERLAP, with conflictingSlotId.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ListResponse<AvailabilitySlotResponse>> add(AccountAuthentication authentication,
			@Valid @RequestBody CreateAvailabilityRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new ListResponse<>(this.slots.create(staffId(authentication), request, accountId(authentication))));
	}

	@PatchMapping("/{slotId}")
	@PreAuthorize(EDIT_OWN)
	@Operation(summary = "Change my availability", operationId = "updateMyAvailability",
			description = "Changes the times or the end date of a slot. Weekly hours that already applied before "
					+ "today keep their past times: they end yesterday and the response is a new slot from today. "
					+ "With date, weekly hours change on that date only: the response is a new one-off slot.")
	@ApiResponse(responseCode = "200", description = "The changed slot.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "AVAILABILITY_OVERLAP, with conflictingSlotId.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	AvailabilitySlotResponse update(AccountAuthentication authentication, @PathVariable UUID slotId,
			@Parameter(description = "Change only this date, today or later.") @RequestParam(required = false)
			@Nullable LocalDate date, @Valid @RequestBody UpdateAvailabilityRequest request) {
		UUID staffId = staffId(authentication);
		return (date == null) ? this.slots.update(staffId, slotId, request, accountId(authentication))
				: this.slots.updateOnDate(staffId, slotId, date, request, accountId(authentication));
	}

	@DeleteMapping("/{slotId}")
	@PreAuthorize(EDIT_OWN)
	@Operation(summary = "Remove my availability", operationId = "deleteMyAvailability",
			description = "Removes a slot from today on. Hours that already applied before today stay in the history. "
					+ "With date, weekly hours are removed on that date only.")
	@ApiResponse(responseCode = "204", description = "Removed.")
	ResponseEntity<Void> delete(AccountAuthentication authentication, @PathVariable UUID slotId,
			@Parameter(description = "Remove only this date, today or later.") @RequestParam(required = false)
			@Nullable LocalDate date) {
		UUID staffId = staffId(authentication);
		if (date == null) {
			this.slots.delete(staffId, slotId);
		}
		else {
			this.slots.deleteOnDate(staffId, slotId, date, accountId(authentication));
		}
		return ResponseEntity.noContent().build();
	}

	private UUID staffId(AccountAuthentication authentication) {
		return this.staffProfiles.getByAccount(accountId(authentication)).id();
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
