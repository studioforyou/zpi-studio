package studioforyou.pl.backend.identity.admin;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.security.AccountAuthenticationToken;

/**
 * Creating administrator accounts. Only for accounts with the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/v1/admin-accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountsController {

	private final AdminAccountService adminAccountService;

	public AdminAccountsController(AdminAccountService adminAccountService) {
		this.adminAccountService = adminAccountService;
	}

	/**
	 * Creates an active administrator account with a generated password, returned only in this
	 * response; no e-mail is sent. 409 {@code ACCOUNT_ALREADY_EXISTS} if the e-mail is taken by an
	 * account or a Firebase user.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public NewAdminAccount create(@Valid @RequestBody CreateAdminRequest request,
			AccountAuthenticationToken authentication) {
		return adminAccountService.create(request.email(), request.firstName(), request.lastName(),
				authentication.account().orElseThrow().id());
	}

}
