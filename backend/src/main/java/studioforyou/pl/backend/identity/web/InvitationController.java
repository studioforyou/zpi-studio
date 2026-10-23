package studioforyou.pl.backend.identity.web;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.invitation.InvitationDetails;
import studioforyou.pl.backend.identity.invitation.InvitationService;

/**
 * E-mail invitations, opened from the link in the invitation e-mail. Both operations are public:
 * the token from the link identifies the invitation. An invalid token gets 404
 * {@code INVITATION_NOT_FOUND}; a used, withdrawn or expired one 410 {@code INVITATION_ALREADY_USED}
 * or {@code INVITATION_EXPIRED}.
 */
@RestController
@RequestMapping("/api/v1/invitations/{token}")
public class InvitationController {

	private final InvitationService invitationService;

	public InvitationController(InvitationService invitationService) {
		this.invitationService = invitationService;
	}

	/** Shows who the invitation is for, before the invited person sets a password. */
	@GetMapping
	@SecurityRequirements
	public InvitationDetails invitation(@PathVariable String token) {
		return invitationService.find(token);
	}

	/**
	 * Sets the password and activates the account. Afterwards the person signs in to the app with
	 * the returned e-mail and the new password.
	 */
	@PostMapping("/accept")
	@SecurityRequirements
	public AcceptInvitationResponse accept(@PathVariable String token,
			@Valid @RequestBody AcceptInvitationRequest request) {
		return new AcceptInvitationResponse(invitationService.accept(token, request.password()));
	}

}
