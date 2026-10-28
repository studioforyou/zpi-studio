package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Administrators assign passes to participants and look them up. Firebase ID tokens are
 * mocked at the {@link JwtDecoder}, as in the authentication tests.
 * <p>
 * The tests are not transactional, so requests run as in production (no open session while
 * the response is written); the data is removed after each test. Without a studio row the
 * studio's time zone is the column default, Europe/Warsaw.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerPassTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String CLIENT_TOKEN = "client-token";

	private static final ZoneId STUDIO_ZONE = ZoneId.of("Europe/Warsaw");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminAccount;

	private UUID adult;

	private UUID child;

	private UUID inactiveAdult;

	private UUID adultProduct;

	private UUID eightEntries;

	private UUID unlimited;

	private UUID childEntries;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "uid-admin", "ADMIN", ADMIN_TOKEN);
		addAccount("client@example.com", "uid-client", "CLIENT", CLIENT_TOKEN);
		adult = insertCustomer("ADULT", "Katarzyna", "Zielińska", "ACTIVE");
		child = insertCustomer("CHILD", "Zosia", "Dąbrowska", "ACTIVE");
		inactiveAdult = insertCustomer("ADULT", "Adam", "Mazur", "INACTIVE");
		adultProduct = insertProduct("Karnet Pole Dance", "ADULT");
		eightEntries = insertVariant(adultProduct, 8, 30, "260.00");
		unlimited = insertVariant(adultProduct, null, 30, "299.00");
		childEntries = insertVariant(insertProduct("Karnet dziecięcy", "CHILD"), 4, 30, "160.00");
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM pass_ledger").update();
		jdbc.sql("DELETE FROM customer_pass").update();
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
	void adminAssignsAPassWithTheVariantTerms() throws Exception {
		LocalDate today = today();
		String body = perform(ADMIN_TOKEN, post(passesOf(adult)), """
				{ "variantId": "%s", "validFrom": "%s", "reason": "Płatność gotówką" }
				""".formatted(eightEntries, today))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith(passesOf(adult) + "/")))
			.andExpect(jsonPath("$.customerId").value(adult.toString()))
			.andExpect(jsonPath("$.variantId").value(eightEntries.toString()))
			.andExpect(jsonPath("$.productName").value("Karnet Pole Dance"))
			.andExpect(jsonPath("$.entriesTotal").value(8))
			.andExpect(jsonPath("$.entriesRemaining").value(8))
			.andExpect(jsonPath("$.pricePaid.amountMinor").value(26000))
			.andExpect(jsonPath("$.pricePaid.currency").value("PLN"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.validFrom").value(today.toString()))
			.andExpect(jsonPath("$.validUntil").value(today.plusDays(29).toString()))
			.andExpect(jsonPath("$.orderItemId").isEmpty())
			.andExpect(jsonPath("$.createdBy").value(adminAccount.toString()))
			.andExpect(jsonPath("$.version").value(0))
			.andReturn()
			.getResponse()
			.getContentAsString();

		Map<String, Object> activation = jdbc.sql("""
				SELECT kind, entries_delta, valid_until_after, reason, actor_account_id
				FROM pass_ledger WHERE pass_id = :id
				""")
			.param("id", UUID.fromString(JsonPath.read(body, "$.id")))
			.query()
			.singleRow();
		assertThat(activation).containsEntry("kind", "ACTIVATION")
			.containsEntry("entries_delta", 8)
			.containsEntry("valid_until_after", java.sql.Date.valueOf(today.plusDays(29)))
			.containsEntry("reason", "Płatność gotówką")
			.containsEntry("actor_account_id", adminAccount);
	}

	@Test
	void aPassStartingLaterIsScheduledAndCanHaveItsOwnPrice() throws Exception {
		LocalDate nextWeek = today().plusDays(7);
		perform(ADMIN_TOKEN, post(passesOf(adult)), """
				{ "variantId": "%s", "validFrom": "%s",
				  "pricePaid": { "amountMinor": 0, "currency": "PLN" }, "reason": "  " }
				""".formatted(unlimited, nextWeek))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.entriesTotal").isEmpty())
			.andExpect(jsonPath("$.entriesRemaining").isEmpty())
			.andExpect(jsonPath("$.pricePaid.amountMinor").value(0))
			.andExpect(jsonPath("$.validUntil").value(nextWeek.plusDays(29).toString()));

		Map<String, Object> activation = jdbc.sql("SELECT entries_delta, reason FROM pass_ledger").query().singleRow();
		assertThat(activation).containsEntry("entries_delta", 0).containsEntry("reason", null);
	}

	@Test
	void aPassThatStartedEarlierIsActiveWhileStillValid() throws Exception {
		perform(ADMIN_TOKEN, post(passesOf(adult)), assignJson(eightEntries, today().minusDays(29)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.validUntil").value(today().toString()));
		perform(ADMIN_TOKEN, post(passesOf(adult)), assignJson(eightEntries, today().minusDays(30)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("validFrom"))
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
	}

	@Test
	void onlyAnAdminCanAssignAndReadPasses() throws Exception {
		perform(CLIENT_TOKEN, post(passesOf(adult)), assignJson(eightEntries, today()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		perform(CLIENT_TOKEN, get(passesOf(adult)), null).andExpect(status().isForbidden());
		perform(CLIENT_TOKEN, get("/api/v1/customers"), null).andExpect(status().isForbidden());
		assertThat(jdbc.sql("SELECT count(*) FROM customer_pass").query(Long.class).single()).isZero();
	}

	@Test
	void thePassMustSuitTheParticipant() throws Exception {
		perform(ADMIN_TOKEN, post(passesOf(adult)), assignJson(childEntries, today()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_AUDIENCE_MISMATCH"));
		perform(ADMIN_TOKEN, post(passesOf(child)), assignJson(eightEntries, today()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_AUDIENCE_MISMATCH"));
		perform(ADMIN_TOKEN, post(passesOf(child)), assignJson(childEntries, today()))
			.andExpect(status().isCreated());

		perform(ADMIN_TOKEN, post(passesOf(inactiveAdult)), assignJson(eightEntries, today()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ACTIVE"));
		perform(ADMIN_TOKEN, post(passesOf(UUID.randomUUID())), assignJson(eightEntries, today()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void onlyVariantsOnSaleCanBeAssigned() throws Exception {
		perform(ADMIN_TOKEN, post(passesOf(adult)), assignJson(UUID.randomUUID(), today()))
			.andExpect(status().isNotFound());

		jdbc.sql("UPDATE pass_product_variant SET is_active = FALSE WHERE id = :id").param("id", unlimited).update();
		perform(ADMIN_TOKEN, post(passesOf(adult)), assignJson(unlimited, today()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_VARIANT_INACTIVE"));

		jdbc.sql("UPDATE pass_product SET is_active = FALSE WHERE id = :id").param("id", adultProduct).update();
		perform(ADMIN_TOKEN, post(passesOf(adult)), assignJson(eightEntries, today()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_PRODUCT_ARCHIVED"));
	}

	@Test
	void invalidAssignmentsAreRejected() throws Exception {
		perform(ADMIN_TOKEN, post(passesOf(adult)), "{ \"reason\": \"%s\" }".formatted("x".repeat(501)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'variantId')].code").value("REQUIRED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'validFrom')].code").value("REQUIRED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'reason')].code").value("TOO_LONG"));
		perform(ADMIN_TOKEN, post(passesOf(adult)), """
				{ "variantId": "%s", "validFrom": "%s", "pricePaid": { "amountMinor": 1000, "currency": "EUR" } }
				""".formatted(eightEntries, today()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("pricePaid.currency"))
			.andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
		assertThat(jdbc.sql("SELECT count(*) FROM customer_pass").query(Long.class).single()).isZero();
	}

	@Test
	void adminListsTheParticipantsPassesNewestFirst() throws Exception {
		String first = assign(adult, eightEntries);
		String second = assign(adult, unlimited);
		assign(child, childEntries);

		perform(ADMIN_TOKEN, get(passesOf(adult)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].id").value(second))
			.andExpect(jsonPath("$.items[1].id").value(first));
		perform(ADMIN_TOKEN, get(passesOf(adult) + "/" + first), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entriesTotal").value(8));
		perform(ADMIN_TOKEN, get(passesOf(child) + "/" + first), null)
			.andExpect(status().isNotFound());
		perform(ADMIN_TOKEN, get(passesOf(UUID.randomUUID())), null)
			.andExpect(status().isNotFound());
	}

	@Test
	void adminSearchesActiveParticipants() throws Exception {
		perform(ADMIN_TOKEN, get("/api/v1/customers?query=zielińska kat"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(adult.toString()))
			.andExpect(jsonPath("$.items[0].kind").value("ADULT"))
			.andExpect(jsonPath("$.items[0].displayName").value("Katarzyna Zielińska"))
			.andExpect(jsonPath("$.totalItems").value(1));
		perform(ADMIN_TOKEN, get("/api/v1/customers?query=mazur"), null)
			.andExpect(jsonPath("$.items", hasSize(0)));
		perform(ADMIN_TOKEN, get("/api/v1/customers?query=%25"), null)
			.andExpect(jsonPath("$.items", hasSize(0)));

		perform(ADMIN_TOKEN, get("/api/v1/customers?size=1&page=1"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].firstName").value("Katarzyna"))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(1))
			.andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.totalPages").value(2));
		perform(ADMIN_TOKEN, get("/api/v1/customers?size=101"), null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	private String assign(UUID customer, UUID variant) throws Exception {
		String body = perform(ADMIN_TOKEN, post(passesOf(customer)), assignJson(variant, today()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private static String passesOf(UUID customer) {
		return "/api/v1/customers/" + customer + "/passes";
	}

	private static String assignJson(UUID variant, LocalDate validFrom) {
		return "{ \"variantId\": \"%s\", \"validFrom\": \"%s\" }".formatted(variant, validFrom);
	}

	private static LocalDate today() {
		return LocalDate.now(STUDIO_ZONE);
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private UUID addAccount(String email, String firebaseUid, String roleCode, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", email)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", firebaseUid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id)
			.param("code", roleCode)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
		return id;
	}

	private UUID insertCustomer(String kind, String firstName, String lastName, String status) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, status)
				VALUES (:kind, :firstName, :lastName, :status)
				RETURNING id
				""")
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

	private UUID insertProduct(String name, String audience) {
		return jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES (:name, 'ENTRY_PASS', :audience) RETURNING id
				""")
			.param("name", name)
			.param("audience", audience)
			.query(UUID.class)
			.single();
	}

	private UUID insertVariant(UUID product, Integer entries, int validityDays, String price) {
		return jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, :entries, :validityDays, CAST(:price AS NUMERIC))
				RETURNING id
				""")
			.param("product", product)
			.param("entries", entries, java.sql.Types.SMALLINT)
			.param("validityDays", validityDays)
			.param("price", price)
			.query(UUID.class)
			.single();
	}

}
