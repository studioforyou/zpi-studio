package studioforyou.pl.backend.identity.security;

import java.util.Collection;
import java.util.Optional;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import studioforyou.pl.backend.identity.AuthenticatedAccount;

/**
 * A verified Firebase ID token together with the application account linked to it.
 * The account is empty when the Firebase user has no account in the application yet.
 */
public class AccountAuthenticationToken extends JwtAuthenticationToken {

	private final transient AuthenticatedAccount account;

	public AccountAuthenticationToken(Jwt jwt, AuthenticatedAccount account,
			Collection<? extends GrantedAuthority> authorities) {
		super(jwt, authorities, jwt.getSubject());
		this.account = account;
	}

	public Optional<AuthenticatedAccount> account() {
		return Optional.ofNullable(account);
	}

}
