package studioforyou.pl.backend.identity.web;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.access.AccessManagement;
import studioforyou.pl.backend.identity.access.OverrideEffect;
import studioforyou.pl.backend.identity.access.PermissionOverride;
import studioforyou.pl.backend.identity.access.RoleAssignment;
import studioforyou.pl.backend.identity.security.AccountAuthenticationToken;
import studioforyou.pl.backend.shared.api.ItemsResponse;

/**
 * Roles and permission overrides of an account. Only administrators can see and change them;
 * every change is recorded in the audit log.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@Tag(name = "identity")
@PreAuthorize("hasRole('ADMIN')")
public class AccountAccessController {

	private final AccessManagement accessManagement;

	public AccountAccessController(AccessManagement accessManagement) {
		this.accessManagement = accessManagement;
	}

	@GetMapping("/roles")
	@Operation(summary = "List the account's roles", operationId = "listAccountRoles")
	@ApiResponse(responseCode = "404", description = "No such account.")
	public ItemsResponse<RoleAssignment> roles(@PathVariable UUID accountId) {
		return new ItemsResponse<>(accessManagement.rolesOf(accountId));
	}

	@PostMapping("/roles")
	@Operation(summary = "Grant a role to the account", operationId = "grantAccountRole")
	@ApiResponse(responseCode = "201", description = "The role was granted.")
	@ApiResponse(responseCode = "404", description = "No such account.")
	@ApiResponse(responseCode = "409", description = "ROLE_ALREADY_GRANTED: the account already has the role.")
	@ApiResponse(responseCode = "422", description = "UNKNOWN_ROLE: no role with this code.")
	public ResponseEntity<RoleAssignment> grantRole(@PathVariable UUID accountId,
			@Valid @RequestBody GrantRoleRequest request, AccountAuthenticationToken authentication) {
		RoleAssignment granted = accessManagement.grantRole(actorId(authentication), accountId, request.code());
		return ResponseEntity
			.created(URI.create("/api/v1/accounts/" + accountId + "/roles/" + granted.code()))
			.body(granted);
	}

	@DeleteMapping("/roles/{roleCode}")
	@Operation(summary = "Revoke a role from the account", operationId = "revokeAccountRole",
			description = "The ADMIN role cannot be revoked from the last active administrator.")
	@ApiResponse(responseCode = "204", description = "The role was revoked.")
	@ApiResponse(responseCode = "404", description = "No such account, or the account does not have the role.")
	@ApiResponse(responseCode = "422", description = "LAST_ACTIVE_ADMIN: this is the last active administrator.")
	public ResponseEntity<Void> revokeRole(@PathVariable UUID accountId, @PathVariable String roleCode,
			AccountAuthenticationToken authentication) {
		accessManagement.revokeRole(actorId(authentication), accountId, roleCode);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/permission-overrides")
	@Operation(summary = "List the account's permission overrides", operationId = "listAccountPermissionOverrides")
	@ApiResponse(responseCode = "404", description = "No such account.")
	public ItemsResponse<PermissionOverride> permissionOverrides(@PathVariable UUID accountId) {
		return new ItemsResponse<>(accessManagement.permissionOverridesOf(accountId));
	}

	@PostMapping("/permission-overrides")
	@Operation(summary = "Give a permission to the account or take it away", operationId = "addAccountPermissionOverride",
			description = "GRANT gives the account a permission none of its roles gives; REVOKE takes away "
					+ "a permission its roles give. To change an override, remove it and add a new one.")
	@ApiResponse(responseCode = "201", description = "The override was added.")
	@ApiResponse(responseCode = "404", description = "No such account.")
	@ApiResponse(responseCode = "409",
			description = "PERMISSION_OVERRIDE_EXISTS: the account already has an override of this permission.")
	@ApiResponse(responseCode = "422", description = "UNKNOWN_PERMISSION: no permission with this code.")
	public ResponseEntity<PermissionOverride> addPermissionOverride(@PathVariable UUID accountId,
			@Valid @RequestBody AddPermissionOverrideRequest request, AccountAuthenticationToken authentication) {
		PermissionOverride added = accessManagement.addPermissionOverride(actorId(authentication), accountId,
				request.permission(), request.effect());
		return ResponseEntity
			.created(URI.create("/api/v1/accounts/" + accountId + "/permission-overrides/" + added.permission()))
			.body(added);
	}

	@DeleteMapping("/permission-overrides/{permissionCode}")
	@Operation(summary = "Remove a permission override from the account",
			operationId = "removeAccountPermissionOverride")
	@ApiResponse(responseCode = "204", description = "The override was removed.")
	@ApiResponse(responseCode = "404", description = "No such account, or no override of this permission.")
	public ResponseEntity<Void> removePermissionOverride(@PathVariable UUID accountId,
			@PathVariable String permissionCode, AccountAuthenticationToken authentication) {
		accessManagement.removePermissionOverride(actorId(authentication), accountId, permissionCode);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/permissions")
	@Operation(summary = "List the account's effective permissions", operationId = "listAccountPermissions",
			description = "The permissions of the account's roles with its overrides applied.")
	@ApiResponse(responseCode = "404", description = "No such account.")
	public ItemsResponse<String> permissions(@PathVariable UUID accountId) {
		return new ItemsResponse<>(accessManagement.effectivePermissionsOf(accountId));
	}

	// Only ACTIVE accounts get past the security filter, so the caller always has an account here.
	private static UUID actorId(AccountAuthenticationToken authentication) {
		return authentication.account().orElseThrow().id();
	}

	@Schema(description = "The role to grant.")
	public record GrantRoleRequest(@NotBlank @Schema(example = "TRAINER") String code) {
	}

	@Schema(description = "The permission to give or take away.")
	public record AddPermissionOverrideRequest(@NotBlank @Schema(example = "SCHEDULE_MANAGE_ALL") String permission,
			@NotNull OverrideEffect effect) {
	}

}
