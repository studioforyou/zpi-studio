package studioforyou.pl.backend.identity.accounts;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.AccountStatus;
import studioforyou.pl.backend.identity.security.AccountAuthenticationToken;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Accounts of clients and staff, managed by administrators. Only for accounts with the
 * {@code ADMIN} role. New accounts are created by {@code POST /api/v1/invitations} (clients,
 * trainers) and {@code POST /api/v1/admin-accounts} (administrators).
 */
@RestController
@RequestMapping("/api/v1/accounts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "accounts")
public class AccountsController {

	private final AccountManagementService accounts;

	AccountsController(AccountManagementService accounts) {
		this.accounts = accounts;
	}

	@GetMapping
	@Operation(summary = "Search people with accounts", operationId = "searchAccounts",
			description = "Account owners and the children of their accounts, one row per person, sorted by last and first name. Closed accounts only when asked for with status=CLOSED.")
	public PageResponse<AccountSummary> search(
			@Parameter(description = "Part of the first and/or last name, the e-mail or the phone number.")
			@RequestParam(required = false) @Nullable String query,
			@Parameter(description = "Only people with this role; CHILD for children.")
			@RequestParam(required = false) @Nullable @Pattern(regexp = "CLIENT|TRAINER|ADMIN|CHILD") String role,
			@Parameter(description = "Only accounts with this status; all but CLOSED when missing.")
			@RequestParam(required = false) @Nullable AccountStatus status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return accounts.search(query, role, status, page, size);
	}

	@GetMapping("/{accountId}")
	@Operation(summary = "Get an account", operationId = "getAccount")
	public AccountDetails get(@PathVariable UUID accountId) {
		return accounts.get(accountId);
	}

	/**
	 * 409 {@code ACCOUNT_ALREADY_EXISTS} if the e-mail is taken, 409 {@code ACCOUNT_STATUS_CONFLICT}
	 * for a closed account.
	 */
	@PutMapping("/{accountId}")
	@Operation(summary = "Update an account's personal and contact details", operationId = "updateAccount")
	public AccountDetails update(@PathVariable UUID accountId, @Valid @RequestBody UpdateAccountRequest request) {
		return accounts.update(accountId, request);
	}

	/**
	 * 404 without a staff profile, 409 {@code ACCOUNT_STATUS_CONFLICT} for a closed account.
	 */
	@PutMapping("/{accountId}/staff-profile")
	@Operation(summary = "Update an account's staff profile", operationId = "updateAccountStaffProfile",
			description = "The names are also saved in the account's own client profile.")
	public AccountDetails updateStaffProfile(@PathVariable UUID accountId,
			@Valid @RequestBody UpdateStaffDetailsRequest request) {
		return accounts.updateStaffProfile(accountId, request);
	}

	/**
	 * 404 unless the account is the child's guardian, 409 {@code ACCOUNT_STATUS_CONFLICT} for a closed
	 * account.
	 */
	@PutMapping("/{accountId}/children/{childProfileId}")
	@Operation(summary = "Update a child of an account", operationId = "updateAccountChild")
	public AccountDetails updateChild(@PathVariable UUID accountId, @PathVariable UUID childProfileId,
			@Valid @RequestBody UpdateChildRequest request) {
		return accounts.updateChild(accountId, childProfileId, request);
	}

	/**
	 * 409 {@code ACCOUNT_STATUS_CONFLICT} unless the account is {@code ACTIVE}; 422
	 * {@code OWN_ACCOUNT_CHANGE} for the caller's own account.
	 */
	@PostMapping("/{accountId}/suspend")
	@Operation(summary = "Suspend an active account", operationId = "suspendAccount")
	public AccountDetails suspend(@PathVariable UUID accountId,
			@Valid @RequestBody(required = false) @Nullable StatusChangeRequest request,
			AccountAuthenticationToken authentication) {
		return accounts.suspend(accountId, callerId(authentication), reason(request));
	}

	/**
	 * 409 {@code ACCOUNT_STATUS_CONFLICT} unless the account is {@code SUSPENDED}.
	 */
	@PostMapping("/{accountId}/reactivate")
	@Operation(summary = "Reactivate a suspended account", operationId = "reactivateAccount")
	public AccountDetails reactivate(@PathVariable UUID accountId,
			@Valid @RequestBody(required = false) @Nullable StatusChangeRequest request,
			AccountAuthenticationToken authentication) {
		return accounts.reactivate(accountId, callerId(authentication), reason(request));
	}

	/**
	 * Closes the account (see {@link AccountManagementService#close}); 409 {@code ACCOUNT_STATUS_CONFLICT}
	 * if it is already closed, 422 {@code OWN_ACCOUNT_CHANGE} for the caller's own account.
	 */
	@DeleteMapping("/{accountId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Close (delete) an account", operationId = "closeAccount")
	public void close(@PathVariable UUID accountId, @RequestParam(required = false) @Nullable String reason,
			AccountAuthenticationToken authentication) {
		accounts.close(accountId, callerId(authentication), reason);
	}

	private static UUID callerId(AccountAuthenticationToken authentication) {
		return authentication.account().orElseThrow().id();
	}

	private static @Nullable String reason(@Nullable StatusChangeRequest request) {
		return (request == null) ? null : request.reason();
	}

}
