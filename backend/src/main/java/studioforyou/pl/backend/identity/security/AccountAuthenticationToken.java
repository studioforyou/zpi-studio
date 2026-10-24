package studioforyou.pl.backend.identity.security;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import studioforyou.pl.backend.identity.AuthenticatedAccount;
import studioforyou.pl.backend.shared.api.AccountAuthentication;

/**
 * A verified Firebase ID token together with the application account linked to it.
 * The account is empty when the Firebase user has no account in the application yet.
 */
public class AccountAuthenticationToken extends JwtAuthenticationToken implements AccountAuthentication {

	private final transient AuthenticatedAccount account;

	public AccountAuthenticationToken(Jwt jwt, AuthenticatedAccount account,
			Collection<? extends GrantedAuthority> authorities) {
		super(jwt, authorities, jwt.getSubject());
		this.account = account;
	}

	public Optional<AuthenticatedAccount> account() {
		return Optional.ofNullable(account);
	}

	@Override
	public Optional<UUID> activeAccountId() {
		return account()
			.filter(ignored -> isAuthenticated())
			.filter(AuthenticatedAccount::isActive)
			.map(AuthenticatedAccount::id);
	}

}
