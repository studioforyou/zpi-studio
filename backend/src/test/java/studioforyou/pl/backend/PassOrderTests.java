package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

/**
 * Clients order a pass for themselves or a child and pay for it with the simulated payment.
 * Firebase ID tokens are mocked at the {@link JwtDecoder}, as in the authentication tests.
 * Without a studio row the studio's time zone is the column default, Europe/Warsaw.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PassOrderTests {

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
		jdbc.sql("DELETE FROM pass_ledger").update();
		jdbc.sql("DELETE FROM customer_pass").update();
		jdbc.sql("DELETE FROM payment").update();
		jdbc.sql("DELETE FROM order_item").update();
		jdbc.sql("DELETE FROM customer_order").update();
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
	void anOrderedPassWaitsForThePaymentAndIsActivatedWhenPaid() throws Exception {
		String body = order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startsOn", today(), "termsAccepted", true))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("CREATED"))
			.andExpect(jsonPath("$.customerId").value(parent.toString()))
			.andExpect(jsonPath("$.variantId").value(variant.toString()))
			.andExpect(jsonPath("$.productName").value("Karnet Pole Dance"))
			.andExpect(jsonPath("$.price.amountMinor").value(26000))
			.andExpect(jsonPath("$.price.currency").value("PLN"))
			.andExpect(jsonPath("$.paidAt").isEmpty())
			.andExpect(jsonPath("$.pass.status").value("PENDING_PAYMENT"))
			.andExpect(jsonPath("$.pass.validFrom").value(today().toString()))
			.andExpect(jsonPath("$.pass.validUntil").value(today().plusDays(29).toString()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID orderId = UUID.fromString(JsonPath.read(body, "$.id"));
		UUID passId = UUID.fromString(JsonPath.read(body, "$.pass.id"));

		assertThat(jdbc.sql("SELECT buyer_id FROM customer_order WHERE id = :id").param("id", orderId)
			.query(UUID.class).single()).isEqualTo(parent);
		assertThat(jdbc.sql("""
				SELECT i.beneficiary_id FROM customer_pass p JOIN order_item i ON i.id = p.order_item_id
				WHERE p.id = :id
				""").param("id", passId).query(UUID.class).single()).isEqualTo(parent);
		assertThat(ledgerEntries(passId)).isZero();
		mvc.perform(get("/api/v1/me/profiles/" + parent + "/passes").header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].status").value("PENDING_PAYMENT"));

		pay(PARENT_TOKEN, orderId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.paidAt").isNotEmpty())
			.andExpect(jsonPath("$.pass.id").value(passId.toString()))
			.andExpect(jsonPath("$.pass.status").value("ACTIVE"));

		Map<String, Object> payment = jdbc.sql("SELECT provider, method, status, amount FROM payment WHERE order_id = :id")
			.param("id", orderId)
			.query()
			.singleRow();
		assertThat(payment).containsEntry("provider", "MOCK")
			.containsEntry("method", "MOCK")
			.containsEntry("status", "CONFIRMED");
		assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo("260.00");
		assertThat(ledgerEntries(passId)).isEqualTo(1);
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id").param("id", passId)
			.query(Integer.class).single()).isEqualTo(8);
	}

	@Test
	void anOrderIsPaidOnlyOnce() throws Exception {
		UUID orderId = placeOrder(Map.of("variantId", variant, "startsOn", today(), "termsAccepted", true));
		pay(PARENT_TOKEN, orderId).andExpect(status().isOk());

		pay(PARENT_TOKEN, orderId)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
		assertThat(jdbc.sql("SELECT count(*) FROM payment").query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void aFutureFirstDaySchedulesThePaidPass() throws Exception {
		UUID orderId = placeOrder(Map.of("variantId", variant, "startsOn", today().plusDays(10), "termsAccepted", true));

		pay(PARENT_TOKEN, orderId)
			.andExpect(jsonPath("$.pass.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.pass.validFrom").value(today().plusDays(10).toString()))
			.andExpect(jsonPath("$.pass.validUntil").value(today().plusDays(39).toString()));
	}

	@Test
	void aPassBoughtAfterTheCurrentOneStartsTheDayAfterItEnds() throws Exception {
		UUID current = insertPass(parent, "ACTIVE", today().minusDays(25), today().plusDays(4));

		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startAfterPassId", current, "termsAccepted", true))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.pass.validFrom").value(today().plusDays(5).toString()))
			.andExpect(jsonPath("$.pass.validUntil").value(today().plusDays(34).toString()));
	}

	@Test
	void aPassBoughtAfterAnExpiredOneStartsToday() throws Exception {
		UUID expired = insertPass(parent, "ACTIVE", today().minusDays(40), today().minusDays(11));

		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startAfterPassId", expired, "termsAccepted", true))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.pass.validFrom").value(today().toString()));
	}

	@Test
	void aGuardianBuysAPassForTheChild() throws Exception {
		String body = order(PARENT_TOKEN, child, Map.of("variantId", variant, "startsOn", today(), "termsAccepted", true))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerId").value(child.toString()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID orderId = UUID.fromString(JsonPath.read(body, "$.id"));

		assertThat(jdbc.sql("SELECT buyer_id FROM customer_order WHERE id = :id").param("id", orderId)
			.query(UUID.class).single()).isEqualTo(parent);
		assertThat(jdbc.sql("SELECT customer_id FROM customer_pass").query(UUID.class).single()).isEqualTo(child);
	}

	@Test
	void theTermsMustBeAccepted() throws Exception {
		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startsOn", today(), "termsAccepted", false))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("termsAccepted"));
		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startsOn", today()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("termsAccepted"));
		assertNothingOrdered();
	}

	@Test
	void exactlyOneWayOfChoosingTheFirstDayIsGiven() throws Exception {
		UUID current = insertPass(parent, "ACTIVE", today(), today().plusDays(29));

		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "termsAccepted", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startsOn"));
		order(PARENT_TOKEN, parent,
				Map.of("variantId", variant, "startsOn", today(), "startAfterPassId", current, "termsAccepted", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startAfterPassId"));
		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startsOn", today().minusDays(1), "termsAccepted", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startsOn"));
		assertNothingOrdered();
	}

	@Test
	void theFollowedPassMustBeAUsablePassOfTheSameParticipant() throws Exception {
		UUID othersPass = insertPass(other, "ACTIVE", today(), today().plusDays(29));
		UUID cancelled = insertPass(parent, "CANCELLED", today(), today().plusDays(29));

		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startAfterPassId", othersPass, "termsAccepted", true))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startAfterPassId"));
		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startAfterPassId", cancelled, "termsAccepted", true))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_NOT_USABLE"));
		assertNothingOrdered();
	}

	@Test
	void aVariantOffSaleCannotBeOrdered() throws Exception {
		jdbc.sql("UPDATE pass_product_variant SET is_active = FALSE").update();

		order(PARENT_TOKEN, parent, Map.of("variantId", variant, "startsOn", today(), "termsAccepted", true))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_VARIANT_INACTIVE"));
		order(PARENT_TOKEN, parent, Map.of("variantId", UUID.randomUUID(), "startsOn", today(), "termsAccepted", true))
			.andExpect(status().isNotFound());
		assertNothingOrdered();
	}

	@Test
	void otherAccountsCannotOrderForTheProfileOrPayTheOrder() throws Exception {
		order(OTHER_TOKEN, parent, Map.of("variantId", variant, "startsOn", today(), "termsAccepted", true))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		order(OTHER_TOKEN, child, Map.of("variantId", variant, "startsOn", today(), "termsAccepted", true))
			.andExpect(status().isNotFound());
		UUID orderId = placeOrder(Map.of("variantId", variant, "startsOn", today(), "termsAccepted", true));

		pay(OTHER_TOKEN, orderId).andExpect(status().isNotFound());
		pay(PARENT_TOKEN, UUID.randomUUID()).andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT status FROM customer_order WHERE id = :id").param("id", orderId)
			.query(String.class).single()).isEqualTo("CREATED");
	}

	private void assertNothingOrdered() {
		assertThat(jdbc.sql("SELECT count(*) FROM customer_order").query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM order_item").query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM customer_pass WHERE order_item_id IS NOT NULL")
			.query(Integer.class).single()).isZero();
	}

	private UUID placeOrder(Map<String, Object> request) throws Exception {
		String body = order(PARENT_TOKEN, parent, request).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private ResultActions order(String token, UUID profile, Map<String, Object> request) throws Exception {
		StringBuilder json = new StringBuilder("{");
		request.forEach((key, value) -> {
			if (json.length() > 1) {
				json.append(',');
			}
			json.append('"').append(key).append("\":");
			json.append((value instanceof Boolean) ? value.toString() : "\"" + value + "\"");
		});
		json.append('}');
		return mvc.perform(post("/api/v1/me/profiles/" + profile + "/pass-orders")
			.header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.toString()));
	}

	private ResultActions pay(String token, UUID orderId) throws Exception {
		return mvc.perform(post("/api/v1/me/pass-orders/" + orderId + "/mock-payment")
			.header("Authorization", "Bearer " + token));
	}

	private int ledgerEntries(UUID passId) {
		return jdbc.sql("SELECT count(*) FROM pass_ledger WHERE pass_id = :id AND kind = 'ACTIVATION'")
			.param("id", passId)
			.query(Integer.class)
			.single();
	}

	private static LocalDate today() {
		return LocalDate.now(STUDIO_ZONE);
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

	private UUID insertPass(UUID customer, String status, LocalDate validFrom, LocalDate validUntil) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				  price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'Karnet Pole Dance', 8, 5, 260.00, :status, :validFrom, :validUntil)
				RETURNING id
				""")
			.param("customer", customer)
			.param("variant", variant)
			.param("status", status)
			.param("validFrom", validFrom)
			.param("validUntil", validUntil)
			.query(UUID.class)
			.single();
	}

}
