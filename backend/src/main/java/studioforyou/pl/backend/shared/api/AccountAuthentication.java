package studioforyou.pl.backend.shared.api;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;

/**
 * The backend account resolved by authentication, without exposing the identity module's
 * token implementation or account model to profile owners.
 */
public interface AccountAuthentication extends Authentication {

	/** The linked account's ID, only when its backend status allows access. */
	Optional<UUID> activeAccountId();

}
