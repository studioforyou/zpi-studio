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
 */
public record AuthenticatedAccount(UUID id, String email, AccountStatus status, Set<String> roles) {

	public boolean isActive() {
		return status == AccountStatus.ACTIVE;
	}

}
