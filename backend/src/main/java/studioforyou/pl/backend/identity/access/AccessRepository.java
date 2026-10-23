package studioforyou.pl.backend.identity.access;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Roles, permissions and their assignments to accounts ({@code role}, {@code permission},
 * {@code role_permission}, {@code account_role}, {@code account_permission_override}).
 */
@Repository
class AccessRepository {

	private final JdbcClient jdbc;

	AccessRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	List<RoleRow> findRoles() {
		return jdbc.sql("SELECT id, code, display_name FROM role ORDER BY id").query(RoleRow.class).list();
	}

	Optional<RoleRow> findRole(String code) {
		return jdbc.sql("SELECT id, code, display_name FROM role WHERE code = :code")
			.param("code", code)
			.query(RoleRow.class)
			.optional();
	}

	/** Locks the role row, so concurrent changes of the role's holders run one at a time. */
	void lockRole(short roleId) {
		jdbc.sql("SELECT id FROM role WHERE id = :id FOR UPDATE").param("id", roleId).query(Short.class).single();
	}

	List<String> findDefaultPermissionCodes(short roleId) {
		return jdbc.sql("""
				SELECT p.code
				FROM role_permission rp
				JOIN permission p ON p.id = rp.permission_id
				WHERE rp.role_id = :roleId
				ORDER BY p.id
				""")
			.param("roleId", roleId)
			.query(String.class)
			.list();
	}

	List<PermissionRow> findPermissions() {
		return jdbc.sql("SELECT id, code, category, description FROM permission ORDER BY id")
			.query(PermissionRow.class)
			.list();
	}

	Optional<PermissionRow> findPermission(String code) {
		return jdbc.sql("SELECT id, code, category, description FROM permission WHERE code = :code")
			.param("code", code)
			.query(PermissionRow.class)
			.optional();
	}

	Optional<String> findAccountStatus(UUID accountId) {
		return jdbc.sql("SELECT status FROM account WHERE id = :id")
			.param("id", accountId)
			.query(String.class)
			.optional();
	}

	List<RoleAssignment> findRoleAssignments(UUID accountId) {
		return jdbc.sql("""
				SELECT r.code, r.display_name, ar.granted_by, ar.granted_at
				FROM account_role ar
				JOIN role r ON r.id = ar.role_id
				WHERE ar.account_id = :accountId
				ORDER BY r.id
				""")
			.param("accountId", accountId)
			.query((rs, rowNum) -> new RoleAssignment(rs.getString("code"), rs.getString("display_name"),
					rs.getObject("granted_by", UUID.class), rs.getTimestamp("granted_at").toInstant()))
			.list();
	}

	/** Adds the role unless the account already has it; returns whether it was added. */
	boolean insertRoleAssignment(UUID accountId, short roleId, UUID grantedBy) {
		return jdbc.sql("""
				INSERT INTO account_role (account_id, role_id, granted_by)
				VALUES (:accountId, :roleId, :grantedBy)
				ON CONFLICT DO NOTHING
				""")
			.param("accountId", accountId)
			.param("roleId", roleId)
			.param("grantedBy", grantedBy)
			.update() == 1;
	}

	/** Removes the role; returns whether the account had it. */
	boolean deleteRoleAssignment(UUID accountId, short roleId) {
		return jdbc.sql("DELETE FROM account_role WHERE account_id = :accountId AND role_id = :roleId")
			.param("accountId", accountId)
			.param("roleId", roleId)
			.update() == 1;
	}

	/** Number of {@code ACTIVE} accounts other than the given one that have the role. */
	long countOtherActiveHolders(short roleId, UUID accountId) {
		return jdbc.sql("""
				SELECT count(*)
				FROM account_role ar
				JOIN account a ON a.id = ar.account_id
				WHERE ar.role_id = :roleId AND a.status = 'ACTIVE' AND a.id <> :accountId
				""")
			.param("roleId", roleId)
			.param("accountId", accountId)
			.query(Long.class)
			.single();
	}

	List<PermissionOverride> findPermissionOverrides(UUID accountId) {
		return jdbc.sql("""
				SELECT p.code, o.effect, o.granted_by, o.granted_at
				FROM account_permission_override o
				JOIN permission p ON p.id = o.permission_id
				WHERE o.account_id = :accountId
				ORDER BY p.id
				""")
			.param("accountId", accountId)
			.query((rs, rowNum) -> new PermissionOverride(rs.getString("code"),
					OverrideEffect.valueOf(rs.getString("effect")), rs.getObject("granted_by", UUID.class),
					rs.getTimestamp("granted_at").toInstant()))
			.list();
	}

	/** Adds the override unless the account already has one for the permission; returns whether it was added. */
	boolean insertPermissionOverride(UUID accountId, short permissionId, OverrideEffect effect, UUID grantedBy) {
		return jdbc.sql("""
				INSERT INTO account_permission_override (account_id, permission_id, effect, granted_by)
				VALUES (:accountId, :permissionId, :effect, :grantedBy)
				ON CONFLICT DO NOTHING
				""")
			.param("accountId", accountId)
			.param("permissionId", permissionId)
			.param("effect", effect.name())
			.param("grantedBy", grantedBy)
			.update() == 1;
	}

	/** Removes the override and returns its effect, or {@code null} when there was none. */
	@Nullable OverrideEffect deletePermissionOverride(UUID accountId, short permissionId) {
		return jdbc.sql("""
				DELETE FROM account_permission_override
				WHERE account_id = :accountId AND permission_id = :permissionId
				RETURNING effect
				""")
			.param("accountId", accountId)
			.param("permissionId", permissionId)
			.query(String.class)
			.optional()
			.map(OverrideEffect::valueOf)
			.orElse(null);
	}

	record RoleRow(short id, String code, String displayName) {
	}

	record PermissionRow(short id, String code, String category, String description) {
	}

}
