package studioforyou.pl.backend.identity;

import java.util.Set;
import java.util.UUID;

/**
 * The application account behind a verified identity provider token.
 *
 * @param id the account id
 * @param email the account's login e-mail
 * @param status the account's status
 * @param roles codes of the account's roles, e.g. {@code CLIENT}, {@code TRAINER}
 * @param permissions codes of the account's effective permissions: its roles' defaults
 * with the account's own overrides applied
 */
public record AuthenticatedAccount(UUID id, String email, AccountStatus status, Set<String> roles,
		Set<String> permissions) {

	/** Code of the role with full access to the studio's administration and every permission. */
	public static final String ADMIN_ROLE = "ADMIN";

	public boolean isActive() {
		return status == AccountStatus.ACTIVE;
	}

}
