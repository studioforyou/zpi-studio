package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import studioforyou.pl.backend.identity.AccountLookup;

/**
 * Runs without a test transaction, like a real request, so every lookup takes its own
 * connections from a pool of two.
 */
@SpringBootTest(properties = { "spring.datasource.hikari.maximum-pool-size=2",
		"spring.datasource.hikari.connection-timeout=1000" })
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class AccountLookupTests {

	@Autowired
	private AccountLookup accountLookup;

	@Test
	void lookupsGiveTheirConnectionsBack() {
		for (int i = 0; i < 10; i++) {
			assertThat(accountLookup.findByFirebaseUid("seed-uid-beata-lis"))
				.hasValueSatisfying(account -> assertThat(account.roles()).containsExactly("ADMIN"));
		}
	}

}
