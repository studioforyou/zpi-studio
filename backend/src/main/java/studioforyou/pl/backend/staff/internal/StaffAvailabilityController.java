package studioforyou.pl.backend.staff.internal;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.AvailabilitySlotResponse;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.CreateAvailabilityRequest;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.UpdateAvailabilityRequest;

/**
 * Administrators manage the working hours of any staff member, with the same rules trainers
 * follow for their own.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/staff/{staffId}/availability")
@Tag(name = "staff")
@ApiResponse(responseCode = "404", description = "The staff member or the slot does not exist.",
		content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class StaffAvailabilityController {

	private final AvailabilitySlots slots;

	StaffAvailabilityController(AvailabilitySlots slots) {
		this.slots = slots;
	}

	@GetMapping
	@Operation(summary = "List a staff member's availability", operationId = "listStaffAvailability",
			description = "Weekly hours and one-off days that apply today or later, as in listMyAvailability.")
	@ApiResponse(responseCode = "200", description = "The staff member's availability.")
	ListResponse<AvailabilitySlotResponse> list(@PathVariable UUID staffId) {
		return new ListResponse<>(this.slots.list(staffId));
	}

	@PostMapping
	@Operation(summary = "Add a staff member's availability", operationId = "addStaffAvailability")
	@ApiResponse(responseCode = "201", description = "The added slot.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "AVAILABILITY_OVERLAP, with conflictingSlotId.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<AvailabilitySlotResponse> add(AccountAuthentication authentication, @PathVariable UUID staffId,
			@Valid @RequestBody CreateAvailabilityRequest request) {
		return OwnAvailabilityController.created(this.slots.create(staffId, request, accountId(authentication)));
	}

	@PatchMapping("/{slotId}")
	@Operation(summary = "Change a staff member's availability", operationId = "updateStaffAvailability",
			description = "As updateMyAvailability: past hours are kept and the response may be a new slot.")
	@ApiResponse(responseCode = "200", description = "The changed slot.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "AVAILABILITY_OVERLAP, with conflictingSlotId.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	AvailabilitySlotResponse update(AccountAuthentication authentication, @PathVariable UUID staffId,
			@PathVariable UUID slotId, @Valid @RequestBody UpdateAvailabilityRequest request) {
		return this.slots.update(staffId, slotId, request, accountId(authentication));
	}

	@DeleteMapping("/{slotId}")
	@Operation(summary = "Remove a staff member's availability", operationId = "deleteStaffAvailability")
	@ApiResponse(responseCode = "204", description = "Removed.")
	ResponseEntity<Void> delete(@PathVariable UUID staffId, @PathVariable UUID slotId) {
		this.slots.delete(staffId, slotId);
		return ResponseEntity.noContent().build();
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
