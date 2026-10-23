package studioforyou.pl.backend.identity.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.invitation.InvitationService;
import studioforyou.pl.backend.identity.invitation.NewInvitation;
import studioforyou.pl.backend.identity.security.AccountAuthenticationToken;

/**
 * Sending e-mail invitations. Only for accounts with the {@code ADMIN} role (see {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationsController {

	private final InvitationService invitationService;

	public InvitationsController(InvitationService invitationService) {
		this.invitationService = invitationService;
	}

	/**
	 * Creates an invited account (client or trainer) with its profile and sends the invitation e-mail.
	 * 409 {@code ACCOUNT_ALREADY_EXISTS} if the e-mail is taken; 502 {@code EMAIL_NOT_SENT} if the
	 * e-mail could not be sent (nothing is saved then).
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public NewInvitation invite(@Valid @RequestBody InviteRequest request, AccountAuthenticationToken authentication) {
		return invitationService.invite(request.email(), request.firstName(), request.lastName(), request.role(),
				authentication.account().orElseThrow().id());
	}

}
