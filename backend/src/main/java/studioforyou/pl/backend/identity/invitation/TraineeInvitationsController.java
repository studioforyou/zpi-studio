package studioforyou.pl.backend.identity.invitation;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Trainers create client accounts for the people they start working with. Needs the
 * {@code TRAINER} role and the {@code CUSTOMER_ACCOUNT_CREATE} permission. The trainer comes from
 * the account, never from the request.
 */
@RestController
@RequestMapping("/api/v1/me/trainees/invitations")
@Tag(name = "customer")
@PreAuthorize("hasRole('TRAINER') and hasAuthority('CUSTOMER_ACCOUNT_CREATE')")
public class TraineeInvitationsController {

	private final InvitationService invitationService;

	public TraineeInvitationsController(InvitationService invitationService) {
		this.invitationService = invitationService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a client account for a new trainee", operationId = "inviteOwnTrainee",
			description = "Creates an invited client account with its own participant profile, assigns the "
					+ "participant to the signed-in trainer and e-mails the person a link to set a password. "
					+ "Nothing is saved if any step fails.")
	@ApiResponse(responseCode = "201", description = "The account was created and the invitation sent.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED: not a trainer, or the account lacks the "
			+ "CUSTOMER_ACCOUNT_CREATE permission.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "ACCOUNT_ALREADY_EXISTS: the e-mail is taken; the trainer "
			+ "can find the participant and start working with them instead.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "STAFF_UNAVAILABLE: the trainer's staff profile is not "
			+ "active.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "502", description = "EMAIL_NOT_SENT.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public NewTraineeInvitation invite(@Valid @RequestBody InviteTraineeRequest request,
			AccountAuthentication authentication) {
		UUID trainerAccountId = authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
		return invitationService.inviteTrainee(request.email(), request.firstName(), request.lastName(),
				trainerAccountId);
	}

}
