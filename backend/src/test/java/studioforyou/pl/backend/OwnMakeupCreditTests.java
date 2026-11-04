package studioforyou.pl.backend;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Real PostgreSQL filtering, pagination and account/profile authorization. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional(isolation = Isolation.REPEATABLE_READ)
class OwnMakeupCreditTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID account;

	private UUID own;

	private UUID child;

	private UUID other;

	private UUID variant;

	@BeforeEach
	void setUp() {
		account = addAccount("parent");
		own = addProfile(account, "ADULT");
		other = addProfile(addAccount("other"), "ADULT");
		child = addProfile(null, "CHILD");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", own).param("child", child).update();
		UUID product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('Current catalogue name', 'ENTRY_PASS', 'ANY')
				RETURNING id
				""").query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 100) RETURNING id
				""").param("product", product).query(UUID.class).single();
	}

	@Test
	void listsOnlyUnusedUnexpiredCreditsWithTheirSourcePassName() throws Exception {
		UUID first = addCredit(own, "AVAILABLE", 1);
		addCredit(own, "AVAILABLE", 3);
		addCredit(own, "AVAILABLE", -1);
		addCredit(own, "AVAILABLE", 0); // Exactly at expiry: the transaction timestamp is fixed.
		addCredit(own, "EXPIRED", 24);
		addCredit(own, "USED", 24);
		UUID used = addCredit(own, "AVAILABLE", 24);
		jdbc.sql("UPDATE makeup_credit SET used_at = CURRENT_TIMESTAMP WHERE id = :id").param("id", used).update();
		addCredit(other, "AVAILABLE", 1);

		perform("parent", own, "")
			.andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].id").value(first.toString()))
			.andExpect(jsonPath("$.items[0].productName").value("Name when assigned"))
			.andExpect(jsonPath("$.items[0].sourcePassId").isString())
			.andExpect(jsonPath("$.items[0].grantedAt").isString())
			.andExpect(jsonPath("$.items[0].expiresAt").isString())
			.andExpect(jsonPath("$.items[0].grantedBy").doesNotExist());
	}

	@Test
	void aCreditSurvivesItsSourcePassExpiry() throws Exception {
		addCredit(own, "AVAILABLE", 1);
		jdbc.sql("UPDATE customer_pass SET status = 'EXPIRED', valid_until = CURRENT_DATE - 1 WHERE customer_id = :id")
			.param("id", own).update();
		perform("parent", own, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void pagesInStableExpiryAndIdOrderWhileKeepingTheFullCount() throws Exception {
		UUID first = addCredit(own, "AVAILABLE", 1);
		UUID second = addCredit(own, "AVAILABLE", 1);
		// PostgreSQL UUID ordering is unsigned, unlike UUID.compareTo.
		String earlierId = first.toString().compareTo(second.toString()) < 0 ? first.toString() : second.toString();
		String laterId = earlierId.equals(first.toString()) ? second.toString() : first.toString();
		addCredit(own, "AVAILABLE", 2);
		perform("parent", own, "?size=1")
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(earlierId))
			.andExpect(jsonPath("$.totalItems").value(3)).andExpect(jsonPath("$.totalPages").value(3));
		perform("parent", own, "?page=1&size=1")
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(laterId))
			.andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalItems").value(3));
		perform("parent", own, "?page=10&size=1")
			.andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalItems").value(3));
	}

	@Test
	void aGuardianSeesOnlyTheSelectedChildsCredits() throws Exception {
		addCredit(own, "AVAILABLE", 1);
		UUID credit = addCredit(child, "AVAILABLE", 1);
		perform("parent", child, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].id").value(credit.toString()));
		perform("other", child, "").andExpect(status().isNotFound());
		jdbc.sql("UPDATE guardianship SET ended_at = CURRENT_TIMESTAMP WHERE child_id = :id").param("id", child).update();
		perform("parent", child, "").andExpect(status().isNotFound());
	}

	@Test
	void hidesOtherMissingAndInactiveProfiles() throws Exception {
		perform("parent", other, "").andExpect(status().isNotFound());
		perform("parent", UUID.randomUUID(), "").andExpect(status().isNotFound());
		jdbc.sql("UPDATE customer_profile SET status = 'ARCHIVED' WHERE id = :id").param("id", own).update();
		perform("parent", own, "").andExpect(status().isNotFound());
		perform("parent", child, "").andExpect(status().isNotFound());
	}

	@Test
	void requiresAnActiveSignedInAccount() throws Exception {
		mvc.perform(get(path(own))).andExpect(status().isUnauthorized());
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :id").param("id", account).update();
		perform("parent", own, "").andExpect(status().isForbidden());
	}

	@Test
	void validatesPageBoundsAndReturnsAnEmptyPage() throws Exception {
		perform("parent", own, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalItems").value(0));
		for (String query : new String[] { "?page=-1", "?size=0", "?size=101", "?page=oops" }) {
			perform("parent", own, query).andExpect(status().isBadRequest());
		}
	}

	@Test
	void doesNotExposeAnotherParticipantsPassNameThroughAnInconsistentCredit() throws Exception {
		UUID credit = addCredit(own, "AVAILABLE", 1);
		jdbc.sql("UPDATE makeup_credit SET customer_id = :other WHERE id = :id")
			.param("other", other).param("id", credit).update();
		perform("other", other, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
	}

	private UUID addCredit(UUID profile, String status, int hoursUntilExpiry) {
		UUID pass = jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				  price_paid, status, valid_from, valid_until)
				VALUES (:profile, :variant, 'Name when assigned', 8, 5, 100, 'ACTIVE', CURRENT_DATE - 1, CURRENT_DATE + 30)
				RETURNING id
				""").param("profile", profile).param("variant", variant).query(UUID.class).single();
		return jdbc.sql("""
				INSERT INTO makeup_credit (customer_id, source_pass_id, status, granted_at, expires_at)
				VALUES (:profile, :pass, :status, CURRENT_TIMESTAMP - interval '1 day',
				  CURRENT_TIMESTAMP + :hours * interval '1 hour') RETURNING id
				""").param("profile", profile).param("pass", pass).param("status", status)
			.param("hours", hoursUntilExpiry).query(UUID.class).single();
	}

	private UUID addAccount(String name) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@makeup.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", "makeup-" + name).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", id).update();
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256")
			.subject("makeup-" + name).build());
		return id;
	}

	private UUID addProfile(UUID accountId, String kind) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, 'Makeup', 'Tester') RETURNING id
				""").param("account", accountId, java.sql.Types.OTHER).param("kind", kind).query(UUID.class).single();
	}

	private static String path(UUID profile) {
		return "/api/v1/me/profiles/" + profile + "/makeup-credits";
	}

	private ResultActions perform(String token, UUID profile, String query) throws Exception {
		return mvc.perform(get(path(profile) + query).header("Authorization", "Bearer " + token));
	}

}
