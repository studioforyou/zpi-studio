package studioforyou.pl.backend.identity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
			.query((rs, rowNum) -> {
				UUID id = rs.getObject("id", UUID.class);
				return new AuthenticatedAccount(id, rs.getString("email"),
						AccountStatus.valueOf(rs.getString("status")), findRoleCodes(id));
			})
			.optional();
	}

	private Set<String> findRoleCodes(UUID accountId) {
		return jdbc.sql("""
				SELECT r.code
				FROM account_role ar
				JOIN role r ON r.id = ar.role_id
				WHERE ar.account_id = :accountId
				""")
			.param("accountId", accountId)
			.query(String.class)
			.stream()
			.collect(Collectors.toUnmodifiableSet());
	}

}
