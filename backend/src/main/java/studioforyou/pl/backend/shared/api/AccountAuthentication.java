package studioforyou.pl.backend.shared.api;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * The backend account resolved by authentication, without exposing the identity module's
 * token implementation or account model to profile owners.
 */
public interface AccountAuthentication extends Authentication {

	/** The linked account's ID, only when its backend status allows access. */
	Optional<UUID> activeAccountId();

	/** The active account's ID; without one the request is denied (403 {@code ACCESS_DENIED}). */
	default UUID requireActiveAccountId() {
		return activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
