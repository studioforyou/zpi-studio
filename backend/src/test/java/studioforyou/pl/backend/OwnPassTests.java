package studioforyou.pl.backend;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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

/**
 * Clients see the passes of their own profile and of the children they are guardians of.
 * Firebase ID tokens are mocked at the {@link JwtDecoder}, as in the authentication tests.
 * Without a studio row the studio's time zone is the column default, Europe/Warsaw.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnPassTests {

	private static final String PARENT_TOKEN = "parent-token";

	private static final String OTHER_TOKEN = "other-token";

	private static final ZoneId STUDIO_ZONE = ZoneId.of("Europe/Warsaw");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID parent;

	private UUID child;

	private UUID other;

	private UUID variant;

	@BeforeEach
	void setUp() {
		UUID parentAccount = addAccount("parent@example.com", "uid-parent", PARENT_TOKEN);
		UUID otherAccount = addAccount("other@example.com", "uid-other", OTHER_TOKEN);
		parent = insertCustomer("ADULT", "Katarzyna", "Zielińska", parentAccount);
		other = insertCustomer("ADULT", "Adam", "Mazur", otherAccount);
		child = insertCustomer("CHILD", "Zosia", "Zielińska", null);
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", parent)
			.param("child", child)
			.update();
		UUID product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('Karnet Pole Dance', 'ENTRY_PASS', 'ANY')
				RETURNING id
				""").query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 260.00)
				RETURNING id
				""").param("product", product).query(UUID.class).single();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM customer_pass").update();
		jdbc.sql("DELETE FROM guardianship").update();
		jdbc.sql("DELETE FROM customer_profile").update();
		jdbc.sql("DELETE FROM pass_product_variant").update();
		jdbc.sql("DELETE FROM pass_product").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%@example.com'").update();
	}

	@Test
	void aClientSeesTheirPassesWithEntriesAndValidity() throws Exception {
		UUID pass = insertPass(parent, "ACTIVE", today().minusDays(3), today().plusDays(26), (short) 5);

		perform(PARENT_TOKEN, passesOf(parent))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(pass.toString()))
			.andExpect(jsonPath("$.items[0].customerId").value(parent.toString()))
			.andExpect(jsonPath("$.items[0].productName").value("Karnet Pole Dance"))
			.andExpect(jsonPath("$.items[0].entriesTotal").value(8))
			.andExpect(jsonPath("$.items[0].entriesRemaining").value(5))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$.items[0].validFrom").value(today().minusDays(3).toString()))
			.andExpect(jsonPath("$.items[0].validUntil").value(today().plusDays(26).toString()))
			.andExpect(jsonPath("$.items[0].pricePaid").doesNotExist())
			.andExpect(jsonPath("$.items[0].createdBy").doesNotExist());
	}

	@Test
	void theStatusFollowsTheDatesAndCancelledPassesAreHidden() throws Exception {
		insertPass(parent, "ACTIVE", today().minusDays(30), today().minusDays(1), (short) 2);
		insertPass(parent, "SCHEDULED", today(), today().plusDays(29), (short) 8);
		insertPass(parent, "SCHEDULED", today().plusDays(1), today().plusDays(30), (short) 8);
		insertPass(parent, "CANCELLED", today(), today().plusDays(29), (short) 8);

		perform(PARENT_TOKEN, passesOf(parent))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(3)))
			.andExpect(jsonPath("$.items[?(@.validFrom == '%s')].status".formatted(today().minusDays(30)))
				.value("EXPIRED"))
			.andExpect(jsonPath("$.items[?(@.validFrom == '%s')].status".formatted(today())).value("ACTIVE"))
			.andExpect(jsonPath("$.items[?(@.validFrom == '%s')].status".formatted(today().plusDays(1)))
				.value("SCHEDULED"));
	}

	@Test
	void aGuardianSeesTheChildsPasses() throws Exception {
		insertPass(child, "ACTIVE", today(), today().plusDays(29), (short) 8);

		perform(PARENT_TOKEN, passesOf(child))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].customerId").value(child.toString()));

		jdbc.sql("UPDATE guardianship SET ended_at = now()").update();
		perform(PARENT_TOKEN, passesOf(child)).andExpect(status().isNotFound());
	}

	@Test
	void otherProfilesAreNotFound() throws Exception {
		insertPass(parent, "ACTIVE", today(), today().plusDays(29), (short) 8);

		perform(OTHER_TOKEN, passesOf(parent))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(OTHER_TOKEN, passesOf(child)).andExpect(status().isNotFound());
		perform(OTHER_TOKEN, passesOf(UUID.randomUUID())).andExpect(status().isNotFound());
		perform(OTHER_TOKEN, passesOf(other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)));
	}

	private static String passesOf(UUID profile) {
		return "/api/v1/me/profiles/" + profile + "/passes";
	}

	private static LocalDate today() {
		return LocalDate.now(STUDIO_ZONE);
	}

	private ResultActions perform(String token, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", "Bearer " + token));
	}

	private UUID addAccount(String email, String firebaseUid, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", email)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", firebaseUid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", id)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
		return id;
	}

	private UUID insertCustomer(String kind, String firstName, String lastName, UUID account) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, account_id)
				VALUES (:kind, :firstName, :lastName, :account)
				RETURNING id
				""")
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("account", account, java.sql.Types.OTHER)
			.query(UUID.class)
			.single();
	}

	private UUID insertPass(UUID customer, String status, LocalDate validFrom, LocalDate validUntil,
			short entriesRemaining) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				  price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'Karnet Pole Dance', 8, :remaining, 260.00, :status, :validFrom, :validUntil)
				RETURNING id
				""")
			.param("customer", customer)
			.param("variant", variant)
			.param("remaining", entriesRemaining)
			.param("status", status)
			.param("validFrom", validFrom)
			.param("validUntil", validUntil)
			.query(UUID.class)
			.single();
	}

}
