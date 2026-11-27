package studioforyou.pl.backend.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Role codes of accounts for other modules, e.g. to mark which role a notification concerns for
 * an account that is both a client and a trainer.
 */
@Repository
public class AccountRoles {

	private final JdbcClient jdbc;

	public AccountRoles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Role codes ({@code CLIENT}, {@code TRAINER}, {@code ADMIN}) by account; accounts without roles are left out. */
	public Map<UUID, Set<String>> rolesOf(Collection<UUID> accountIds) {
		Map<UUID, Set<String>> roles = new HashMap<>();
		if (accountIds.isEmpty()) {
			return roles;
		}
		jdbc.sql("""
				SELECT ar.account_id, r.code
				FROM account_role ar
				JOIN role r ON r.id = ar.role_id
				WHERE ar.account_id IN (:accountIds)
				""")
			.param("accountIds", accountIds)
			.query((rs, rowNum) -> Map.entry(rs.getObject("account_id", UUID.class), rs.getString("code")))
			.list()
			.forEach((row) -> roles.computeIfAbsent(row.getKey(), (id) -> new HashSet<>()).add(row.getValue()));
		return roles;
	}

	/** Active trainer accounts, e.g. recipients of offers of a substitution. */
	public Set<UUID> activeTrainers(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) return Set.of();
		return jdbc.sql("""
				SELECT DISTINCT a.id FROM account a
				JOIN account_role ar ON ar.account_id = a.id JOIN role r ON r.id = ar.role_id
				WHERE a.id IN (:ids) AND a.status = 'ACTIVE' AND r.code = 'TRAINER'
				""").param("ids", accountIds).query(UUID.class).set();
	}

	/** The accounts among the given ones whose status is active. */
	public Set<UUID> findActive(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Set.of();
		}
		return jdbc.sql("SELECT id FROM account WHERE id IN (:ids) AND status = 'ACTIVE'")
			.param("ids", accountIds)
			.query(UUID.class)
			.set();
	}

	/** Active accounts that have the role, e.g. {@code CLIENT}. */
	public Set<UUID> findActiveWithRole(String role) {
		return jdbc.sql("""
				SELECT a.id
				FROM account a
				JOIN account_role ar ON ar.account_id = a.id
				JOIN role r ON r.id = ar.role_id
				WHERE r.code = :role AND a.status = 'ACTIVE'
				""")
			.param("role", role)
			.query(UUID.class)
			.set();
	}

}
