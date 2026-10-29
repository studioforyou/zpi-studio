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
 * Participants the signed-in trainer works with. The trainer comes from the account, never from
 * the request, so trainers can assign participants only to themselves.
 */
@RestController
@RequestMapping("/api/v1/me/trainees")
@Tag(name = "customer")
@PreAuthorize("hasRole('TRAINER')")
public class OwnTraineeController {

	private final TrainerAssignmentService assignments;

	public OwnTraineeController(TrainerAssignmentService assignments) {
		this.assignments = assignments;
	}

	@GetMapping
	@Operation(summary = "List my trainees", operationId = "listOwnTrainees",
			description = "Participants currently assigned to the signed-in trainer, sorted by last and first name.")
	@ApiResponse(responseCode = "200", description = "The trainer's trainees.")
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ListResponse<TraineeResponse> list(AccountAuthentication authentication) {
		return new ListResponse<>(
				assignments.listOwnTrainees(accountId(authentication)).stream().map(TraineeResponse::from).toList());
	}

	@GetMapping("/{customerId}")
	@Operation(summary = "Get my trainee", operationId = "getOwnTrainee")
	@ApiResponse(responseCode = "200", description = "The trainee's current assignment.")
	@ApiResponse(responseCode = "404", description = "No such participant, the participant is not assigned to the "
			+ "trainer, or the account has no staff profile.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public TraineeResponse get(@PathVariable UUID customerId, AccountAuthentication authentication) {
		return TraineeResponse.from(assignments.getOwnTrainee(customerId, accountId(authentication)));
	}

	@PostMapping
	@Operation(summary = "Start working with a participant", operationId = "assignOwnTrainee",
			description = "Assigns an active participant to the signed-in trainer. The participant can also work "
					+ "with other trainers.")
	@ApiResponse(responseCode = "201", description = "The new assignment.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such participant, or the account has no staff profile.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "TRAINER_ALREADY_ASSIGNED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CUSTOMER_NOT_ACTIVE or STAFF_UNAVAILABLE (the trainer's "
			+ "staff profile is not active).", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<TraineeResponse> assign(@Valid @RequestBody AssignTraineeRequest request,
			AccountAuthentication authentication) {
		var assigned = assignments.assignToSelf(request.customerId(), accountId(authentication));
		return ResponseEntity.created(URI.create("/api/v1/me/trainees/" + request.customerId()))
			.body(TraineeResponse.from(assigned));
	}

	@DeleteMapping("/{customerId}")
	@Operation(summary = "Stop working with a participant", operationId = "unassignOwnTrainee",
			description = "Ends the assignment; it is kept as history.")
	@ApiResponse(responseCode = "204", description = "The assignment has ended.")
	@ApiResponse(responseCode = "404", description = "No such participant, the participant is not assigned to the "
			+ "trainer, or the account has no staff profile.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<Void> unassign(@PathVariable UUID customerId, AccountAuthentication authentication) {
		assignments.unassignFromSelf(customerId, accountId(authentication));
		return ResponseEntity.noContent().build();
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
