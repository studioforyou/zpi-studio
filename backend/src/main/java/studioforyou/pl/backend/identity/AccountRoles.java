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

}
