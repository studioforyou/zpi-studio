package studioforyou.pl.backend.identity.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import studioforyou.pl.backend.identity.AccountLookup;
import studioforyou.pl.backend.identity.AuthenticatedAccount;

/**
 * Maps a verified Firebase ID token to the linked application account. The token only
 * proves who the user is; status and roles always come from the database.
 */
@Component
public class FirebaseJwtAuthenticationConverter implements Converter<Jwt, AccountAuthenticationToken> {

	/** Granted only to accounts with status {@code ACTIVE}. */
	public static final String ACTIVE_ACCOUNT = "ACCOUNT_ACTIVE";

	private final AccountLookup accountLookup;

	public FirebaseJwtAuthenticationConverter(AccountLookup accountLookup) {
		this.accountLookup = accountLookup;
	}

	@Override
	public AccountAuthenticationToken convert(Jwt jwt) {
		AuthenticatedAccount account = accountLookup.findByFirebaseUid(jwt.getSubject()).orElse(null);
		return new AccountAuthenticationToken(jwt, account, authorities(account));
	}

	private static List<GrantedAuthority> authorities(AuthenticatedAccount account) {
		List<GrantedAuthority> authorities = new ArrayList<>();
		if (account != null && account.isActive()) {
			authorities.add(new SimpleGrantedAuthority(ACTIVE_ACCOUNT));
			account.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
		}
		return authorities;
	}

}
