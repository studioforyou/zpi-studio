package studioforyou.pl.backend.identity.access;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.identity.AccountLookup;
import studioforyou.pl.backend.identity.AuthenticatedAccount;
import studioforyou.pl.backend.identity.access.AccessRepository.PermissionRow;
import studioforyou.pl.backend.identity.access.AccessRepository.RoleRow;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Grants and revokes roles and permission overrides of accounts. Every change is recorded
 * in the audit log in the same transaction.
 * <p>
 * The studio always keeps at least one {@code ACTIVE} administrator: the {@code ADMIN} role
 * cannot be revoked from the last one. An administrator always has every permission, so
 * permission overrides make no difference to an account with the {@code ADMIN} role.
 */
@Service
@Transactional
public class AccessManagement {

	private static final String MODULE = "identity";

	private static final String ENTITY_TYPE = "account";

	private final AccessRepository repository;

	private final AccountLookup accountLookup;

	private final AuditLog auditLog;

	AccessManagement(AccessRepository repository, AccountLookup accountLookup, AuditLog auditLog) {
		this.repository = repository;
		this.accountLookup = accountLookup;
		this.auditLog = auditLog;
	}

	/** The roles with their default permissions; {@code ADMIN} with every permission. */
	@Transactional(readOnly = true)
	public List<RoleDefinition> roles() {
		List<String> allPermissions = repository.findPermissions().stream().map(PermissionRow::code).toList();
		return repository.findRoles()
			.stream()
			.map(role -> new RoleDefinition(role.code(), role.displayName(),
					AuthenticatedAccount.ADMIN_ROLE.equals(role.code()) ? allPermissions
							: repository.findDefaultPermissionCodes(role.id())))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<PermissionDefinition> permissions() {
		return repository.findPermissions()
			.stream()
			.map(permission -> new PermissionDefinition(permission.code(), permission.category(),
					permission.description()))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<RoleAssignment> rolesOf(UUID accountId) {
		requireAccount(accountId);
		return repository.findRoleAssignments(accountId);
	}

	@Transactional(readOnly = true)
	public List<PermissionOverride> permissionOverridesOf(UUID accountId) {
		requireAccount(accountId);
		return repository.findPermissionOverrides(accountId);
	}

	/** Effective permission codes of the account, sorted. */
	@Transactional(readOnly = true)
	public List<String> effectivePermissionsOf(UUID accountId) {
		requireAccount(accountId);
		return accountLookup.findPermissionCodes(accountId).stream().sorted().toList();
	}

	public RoleAssignment grantRole(UUID actorId, UUID accountId, String roleCode) {
		requireAccount(accountId);
		RoleRow role = repository.findRole(roleCode)
			.orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_ROLE, "Role " + roleCode + " does not exist."));
		List<String> before = roleCodes(accountId);
		if (!repository.insertRoleAssignment(accountId, role.id(), actorId)) {
			throw new ApiException(ErrorCode.ROLE_ALREADY_GRANTED, "The account already has role " + roleCode + ".");
		}
		audit(actorId, accountId, "ROLE_GRANTED", "roles", before, roleCodes(accountId));
		return repository.findRoleAssignments(accountId)
			.stream()
			.filter(assignment -> assignment.code().equals(roleCode))
			.findFirst()
			.orElseThrow();
	}

	public void revokeRole(UUID actorId, UUID accountId, String roleCode) {
		String status = requireAccount(accountId);
		RoleRow role = repository.findRole(roleCode).orElseThrow(() -> roleNotGranted(roleCode));
		if (AuthenticatedAccount.ADMIN_ROLE.equals(roleCode)) {
			repository.lockRole(role.id());
			if ("ACTIVE".equals(status) && repository.countOtherActiveHolders(role.id(), accountId) == 0
					&& roleCodes(accountId).contains(AuthenticatedAccount.ADMIN_ROLE)) {
				throw new ApiException(ErrorCode.LAST_ACTIVE_ADMIN,
						"The studio must keep at least one active administrator.");
			}
		}
		List<String> before = roleCodes(accountId);
		if (!repository.deleteRoleAssignment(accountId, role.id())) {
			throw roleNotGranted(roleCode);
		}
		audit(actorId, accountId, "ROLE_REVOKED", "roles", before, roleCodes(accountId));
	}

	public PermissionOverride addPermissionOverride(UUID actorId, UUID accountId, String permissionCode,
			OverrideEffect effect) {
		requireAccount(accountId);
		PermissionRow permission = repository.findPermission(permissionCode)
			.orElseThrow(() -> new ApiException(ErrorCode.UNKNOWN_PERMISSION,
					"Permission " + permissionCode + " does not exist."));
		if (!repository.insertPermissionOverride(accountId, permission.id(), effect, actorId)) {
			throw new ApiException(ErrorCode.PERMISSION_OVERRIDE_EXISTS,
					"The account already has an override of " + permissionCode + "; remove it first.");
		}
		audit(actorId, accountId, "PERMISSION_OVERRIDE_ADDED", "permissionOverride." + permissionCode, null,
				effect.name());
		return repository.findPermissionOverrides(accountId)
			.stream()
			.filter(override -> override.permission().equals(permissionCode))
			.findFirst()
			.orElseThrow();
	}

	public void removePermissionOverride(UUID actorId, UUID accountId, String permissionCode) {
		requireAccount(accountId);
		PermissionRow permission = repository.findPermission(permissionCode)
			.orElseThrow(() -> overrideNotFound(permissionCode));
		OverrideEffect removed = repository.deletePermissionOverride(accountId, permission.id());
		if (removed == null) {
			throw overrideNotFound(permissionCode);
		}
		audit(actorId, accountId, "PERMISSION_OVERRIDE_REMOVED", "permissionOverride." + permissionCode,
				removed.name(), null);
	}

	/** Returns the account's status, or fails with 404 when there is no such account. */
	private String requireAccount(UUID accountId) {
		return repository.findAccountStatus(accountId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Account " + accountId + " does not exist."));
	}

	private List<String> roleCodes(UUID accountId) {
		return repository.findRoleAssignments(accountId).stream().map(RoleAssignment::code).sorted().toList();
	}

	private void audit(UUID actorId, UUID accountId, String action, String field, Object oldValue,
			Object newValue) {
		auditLog.append(new AuditLog.Entry(actorId, MODULE, ENTITY_TYPE, accountId, action,
				Map.of(field, new AuditLog.Change(oldValue, newValue)), null));
	}

	private static ApiException roleNotGranted(String roleCode) {
		return new ApiException(ErrorCode.NOT_FOUND, "The account does not have role " + roleCode + ".");
	}

	private static ApiException overrideNotFound(String permissionCode) {
		return new ApiException(ErrorCode.NOT_FOUND, "The account has no override of " + permissionCode + ".");
	}

}
