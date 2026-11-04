package studioforyou.pl.backend.identity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Finds the account linked to an identity provider user ({@code identity_link}).
 */
@Repository
public class AccountLookup {

	/** {@code identity_link.provider} value for Firebase Authentication. */
	public static final String FIREBASE_PROVIDER = "FIREBASE";

	private final JdbcClient jdbc;

	public AccountLookup(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<AuthenticatedAccount> findByFirebaseUid(String uid) {
		return jdbc.sql("""
				SELECT a.id, a.email, a.status
				FROM identity_link l
				JOIN account a ON a.id = l.account_id
				WHERE l.provider = :provider AND l.external_uid = :uid
				""")
			.param("provider", FIREBASE_PROVIDER)
			.param("uid", uid)
			.query((rs, rowNum) -> new AuthenticatedAccount(rs.getObject("id", UUID.class), rs.getString("email"),
					AccountStatus.valueOf(rs.getString("status")), Set.of(), Set.of()))
			.optional()
			// Roles and permissions are read after the account query has given its connection back.
			.map(account -> new AuthenticatedAccount(account.id(), account.email(), account.status(),
					findRoleCodes(account.id()), findPermissionCodes(account.id())));
	}

	private Set<String> findRoleCodes(UUID accountId) {
		// list()/set() close the result set; an unclosed stream() would keep the connection.
		return Set.copyOf(jdbc.sql("""
				SELECT r.code
				FROM account_role ar
				JOIN role r ON r.id = ar.role_id
				WHERE ar.account_id = :accountId
				""")
			.param("accountId", accountId)
			.query(String.class)
			.set());
	}

	/**
	 * Every permission for an {@code ADMIN}; for other accounts the permissions of their
	 * roles plus their {@code GRANT} overrides, minus their {@code REVOKE} overrides.
	 */
	public Set<String> findPermissionCodes(UUID accountId) {
		return Set.copyOf(jdbc.sql("""
				SELECT p.code
				FROM permission p
				WHERE EXISTS (SELECT 1
				              FROM account_role ar
				              JOIN role r ON r.id = ar.role_id
				              WHERE ar.account_id = :accountId AND r.code = :adminRole)
				   OR ((EXISTS (SELECT 1
				               FROM role_permission rp
				               JOIN account_role ar ON ar.role_id = rp.role_id
				               WHERE ar.account_id = :accountId AND rp.permission_id = p.id)
				       OR EXISTS (SELECT 1
				                  FROM account_permission_override o
				                  WHERE o.account_id = :accountId AND o.permission_id = p.id AND o.effect = 'GRANT'))
				  AND NOT EXISTS (SELECT 1
				                  FROM account_permission_override o
				                  WHERE o.account_id = :accountId AND o.permission_id = p.id AND o.effect = 'REVOKE'))
				""")
			.param("accountId", accountId)
			.param("adminRole", AuthenticatedAccount.ADMIN_ROLE)
			.query(String.class)
			.set());
	}

}
