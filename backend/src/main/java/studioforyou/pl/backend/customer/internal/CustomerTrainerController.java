package studioforyou.pl.backend.customer.internal;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;

/**
 * Trainers a participant works with. For now only administrators assign and unassign them.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/trainers")
@Tag(name = "customer")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerTrainerController {

	private final TrainerAssignmentService assignments;

	public CustomerTrainerController(TrainerAssignmentService assignments) {
		this.assignments = assignments;
	}

	@GetMapping
	@Operation(summary = "List a participant's trainers", operationId = "listCustomerTrainers",
			description = "Trainers currently assigned to the participant, longest assigned first.")
	@ApiResponse(responseCode = "200", description = "The participant's trainers.")
	@ApiResponse(responseCode = "404", description = "No such participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ListResponse<CustomerTrainerResponse> list(@PathVariable UUID customerId) {
		return new ListResponse<>(assignments.list(customerId).stream().map(CustomerTrainerResponse::from).toList());
	}

	@GetMapping("/{staffId}")
	@Operation(summary = "Get a participant's trainer", operationId = "getCustomerTrainer")
	@ApiResponse(responseCode = "200", description = "The trainer's current assignment.")
	@ApiResponse(responseCode = "404", description = "No such participant, or the trainer is not assigned to them.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public CustomerTrainerResponse get(@PathVariable UUID customerId, @PathVariable UUID staffId) {
		return CustomerTrainerResponse.from(assignments.get(customerId, staffId));
	}

	@PostMapping
	@Operation(summary = "Assign a trainer to a participant", operationId = "assignCustomerTrainer",
			description = "A participant can work with several trainers. Only an active staff member whose account "
					+ "has the TRAINER role can be assigned.")
	@ApiResponse(responseCode = "201", description = "The trainer's new assignment.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "TRAINER_ALREADY_ASSIGNED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CUSTOMER_NOT_ACTIVE, STAFF_UNAVAILABLE (no such or inactive "
			+ "staff member) or STAFF_NOT_TRAINER.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<CustomerTrainerResponse> assign(@PathVariable UUID customerId,
			@Valid @RequestBody AssignTrainerRequest request, AccountAuthentication authentication) {
		var assigned = assignments.assign(customerId, request.staffId(), accountId(authentication));
		return ResponseEntity.created(URI.create("/api/v1/customers/" + customerId + "/trainers/" + request.staffId()))
			.body(CustomerTrainerResponse.from(assigned));
	}

	@DeleteMapping("/{staffId}")
	@Operation(summary = "Unassign a trainer from a participant", operationId = "unassignCustomerTrainer",
			description = "Ends the assignment; it is kept as history.")
	@ApiResponse(responseCode = "204", description = "The assignment has ended.")
	@ApiResponse(responseCode = "404", description = "No such participant, or the trainer is not assigned to them.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<Void> unassign(@PathVariable UUID customerId, @PathVariable UUID staffId) {
		assignments.unassign(customerId, staffId);
		return ResponseEntity.noContent().build();
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
