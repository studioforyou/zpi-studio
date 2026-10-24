package studioforyou.pl.backend.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.springframework.security.oauth2.jwt.Jwt;

import studioforyou.pl.backend.identity.AccountStatus;
import studioforyou.pl.backend.identity.AuthenticatedAccount;
import studioforyou.pl.backend.shared.api.AccountAuthentication;

class AccountAuthenticationTokenTest {

	@Test
	void activeAccountIdComesFromTheBackendAccountRatherThanTokenClaims() {
		AuthenticatedAccount account = account(AccountStatus.ACTIVE);
		AccountAuthentication authentication = new AccountAuthenticationToken(jwt(), account, List.of());

		assertThat(authentication.activeAccountId()).contains(account.id());
	}

	@ParameterizedTest
	@EnumSource(value = AccountStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
	void inactiveAccountsDoNotExposeAnActiveAccountId(AccountStatus status) {
		AccountAuthentication authentication = new AccountAuthenticationToken(jwt(), account(status), List.of());

		assertThat(authentication.activeAccountId()).isEmpty();
	}

	@Test
	void unlinkedFirebaseUsersDoNotExposeAnAccountId() {
		AccountAuthentication authentication = new AccountAuthenticationToken(jwt(), null, List.of());

		assertThat(authentication.activeAccountId()).isEmpty();
	}

	@Test
	void unauthenticatedTokensDoNotExposeAnActiveAccountId() {
		AccountAuthenticationToken authentication = new AccountAuthenticationToken(jwt(), account(AccountStatus.ACTIVE),
				List.of());
		authentication.setAuthenticated(false);

		assertThat(authentication.activeAccountId()).isEmpty();
	}

	private static AuthenticatedAccount account(AccountStatus status) {
		return new AuthenticatedAccount(UUID.randomUUID(), "trainer@example.com", status, Set.of("TRAINER"));
	}

	private static Jwt jwt() {
		return Jwt.withTokenValue("token").header("alg", "RS256").subject("firebase-uid")
			.claim("accountId", UUID.randomUUID().toString()).claim("status", "ACTIVE").build();
	}

}
