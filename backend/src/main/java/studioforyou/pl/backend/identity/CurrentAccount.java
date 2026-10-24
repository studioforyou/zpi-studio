package studioforyou.pl.backend.identity;

import org.springframework.security.core.Authentication;

import studioforyou.pl.backend.identity.security.AccountAuthenticationToken;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * The account that sends the current request, for modules that record who did something.
 */
public final class CurrentAccount {

	private CurrentAccount() {
	}

	/**
	 * The account behind the request's token. Endpoints that need an active account always
	 * have one, so a missing account means the endpoint is public or misconfigured.
	 */
	public static AuthenticatedAccount of(Authentication authentication) {
		if (authentication instanceof AccountAuthenticationToken token) {
			return token.account()
				.orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND,
						"This user has no account in the application."));
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

}
