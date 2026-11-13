package studioforyou.pl.backend;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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
 * Administrators see which passes clients buy most, by kind, product and price variant (#167).
 * The data lies in January to March 2025, away from the current dates other tests use. Without a
 * studio row the studio's time zone is Europe/Warsaw (UTC+1 then) and its currency PLN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PassSalesReportTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminAccount;

	private UUID client;

	private final List<UUID> products = new ArrayList<>();

	private UUID poleFour;

	private UUID poleEight;

	private UUID single;

	private UUID open;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "ADMIN");
		signIn(adminAccount, "uid-admin", ADMIN_TOKEN);
		signIn(addAccount("trainer@example.com", "TRAINER"), "uid-trainer", TRAINER_TOKEN);
		client = jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name) VALUES ('ADULT', 'Jan', 'Kupujący')
				RETURNING id
				""").query(UUID.class).single();
		UUID pole = insertProduct("Karnet Pole Dance", "ENTRY_PASS");
		poleFour = insertVariant(pole, 4, 30, "180.00");
		poleEight = insertVariant(pole, 8, 30, "260.00");
		single = insertVariant(insertProduct("Wejście jednorazowe", "SINGLE_ENTRY"), 1, 30, "55.00");
		open = insertVariant(insertProduct("Karnet Open", "TIME_PASS"), null, 30, "299.00");
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE customer_id = :id)")
			.param("id", client)
			.update();
		jdbc.sql("DELETE FROM customer_pass WHERE customer_id = :id").param("id", client).update();
		jdbc.sql("DELETE FROM order_item WHERE beneficiary_id = :id").param("id", client).update();
		jdbc.sql("DELETE FROM customer_order WHERE buyer_id = :id").param("id", client).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id = :id").param("id", client).update();
		if (!products.isEmpty()) {
			jdbc.sql("DELETE FROM pass_product_variant WHERE product_id IN (:ids)").param("ids", products).update();
			jdbc.sql("DELETE FROM pass_product WHERE id IN (:ids)").param("ids", products).update();
		}
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%@example.com'").update();
		products.clear();
	}

	@Test
	void reportsPassesSoldByKindProductAndVariant() throws Exception {
		new Sales();

		report("2025-02-01", "2025-02-28").andExpect(status().isOk())
			.andExpect(jsonPath("$.from").value("2025-02-01"))
			.andExpect(jsonPath("$.to").value("2025-02-28"))
			.andExpect(jsonPath("$.currency").value("PLN"))
			// Unpaid and cancelled passes and the sale after midnight in Warsaw are left out.
			.andExpect(jsonPath("$.totals.passes").value(4))
			// The price actually paid: 180 + 180 + 250 (a discount at the desk) + 0 (a gift).
			.andExpect(jsonPath("$.totals.revenueMinor").value(61000))
			.andExpect(jsonPath("$.totals.free").value(1))
			.andExpect(jsonPath("$.totals.online").value(2))
			.andExpect(jsonPath("$.totals.studio").value(2))
			// The 28 days right before.
			.andExpect(jsonPath("$.previous.from").value("2025-01-04"))
			.andExpect(jsonPath("$.previous.to").value("2025-01-31"))
			.andExpect(jsonPath("$.previous.totals.passes").value(1))
			.andExpect(jsonPath("$.previous.totals.revenueMinor").value(5500))
			// Every kind, in a fixed order, also those not sold.
			.andExpect(jsonPath("$.kinds[*].kind",
					contains("ENTRY_PASS", "SINGLE_ENTRY", "PT_PACKAGE", "TIME_PASS", "VOUCHER")))
			.andExpect(jsonPath("$.kinds[0].counts.passes").value(3))
			.andExpect(jsonPath("$.kinds[0].counts.revenueMinor").value(61000))
			.andExpect(jsonPath("$.kinds[1].counts.passes").value(0))
			.andExpect(jsonPath("$.kinds[3].counts.passes").value(1))
			.andExpect(jsonPath("$.kinds[3].counts.free").value(1))
			// The most passes first, each with its variants.
			.andExpect(jsonPath("$.products", hasSize(2)))
			.andExpect(jsonPath("$.products[0].productId").value(products.get(0).toString()))
			.andExpect(jsonPath("$.products[0].name").value("Karnet Pole Dance"))
			.andExpect(jsonPath("$.products[0].kind").value("ENTRY_PASS"))
			.andExpect(jsonPath("$.products[0].counts.passes").value(3))
			.andExpect(jsonPath("$.products[0].counts.online").value(2))
			.andExpect(jsonPath("$.products[0].counts.studio").value(1))
			.andExpect(jsonPath("$.products[0].variants", hasSize(2)))
			.andExpect(jsonPath("$.products[0].variants[0].variantId").value(poleFour.toString()))
			.andExpect(jsonPath("$.products[0].variants[0].entriesCount").value(4))
			.andExpect(jsonPath("$.products[0].variants[0].validityDays").value(30))
			.andExpect(jsonPath("$.products[0].variants[0].priceMinor").value(18000))
			.andExpect(jsonPath("$.products[0].variants[0].counts.passes").value(2))
			.andExpect(jsonPath("$.products[0].variants[0].counts.revenueMinor").value(36000))
			.andExpect(jsonPath("$.products[0].variants[1].variantId").value(poleEight.toString()))
			.andExpect(jsonPath("$.products[0].variants[1].priceMinor").value(26000))
			.andExpect(jsonPath("$.products[0].variants[1].counts.revenueMinor").value(25000))
			.andExpect(jsonPath("$.products[1].name").value("Karnet Open"))
			.andExpect(jsonPath("$.products[1].variants[0].entriesCount").isEmpty())
			// Studio dates with sales, per kind.
			.andExpect(jsonPath("$.days", hasSize(3)))
			.andExpect(jsonPath("$.days[0].date").value("2025-02-03"))
			.andExpect(jsonPath("$.days[0].kind").value("ENTRY_PASS"))
			.andExpect(jsonPath("$.days[1].date").value("2025-02-10"))
			.andExpect(jsonPath("$.days[1].counts.passes").value(2))
			.andExpect(jsonPath("$.days[2].date").value("2025-02-20"))
			.andExpect(jsonPath("$.days[2].kind").value("TIME_PASS"));
	}

	@Test
	void countsASaleOnTheStudioDateOfItsPayment() throws Exception {
		new Sales();

		// Paid at 23:30 UTC on 28 February, which is already 1 March in Warsaw.
		report("2025-03-01", "2025-03-01").andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.passes").value(1))
			.andExpect(jsonPath("$.kinds[1].kind").value("SINGLE_ENTRY"))
			.andExpect(jsonPath("$.kinds[1].counts.passes").value(1))
			.andExpect(jsonPath("$.days[0].date").value("2025-03-01"));
	}

	@Test
	void reportsNothingForAPeriodWithoutSales() throws Exception {
		new Sales();

		report("2024-06-01", "2024-06-30").andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.passes").value(0))
			.andExpect(jsonPath("$.totals.revenueMinor").value(0))
			.andExpect(jsonPath("$.kinds", hasSize(5)))
			.andExpect(jsonPath("$.products", hasSize(0)))
			.andExpect(jsonPath("$.days", hasSize(0)));
	}

	@Test
	void rejectsAReversedOrTooLongPeriod() throws Exception {
		report("2025-03-31", "2025-03-01").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("to"));
		report("2023-01-01", "2025-01-01").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("to"));
		mvc.perform(get("/api/v1/reports/pass-sales").param("from", "2025-03-01")
			.header("Authorization", "Bearer " + ADMIN_TOKEN)).andExpect(status().isBadRequest());
	}

	@Test
	void isOnlyForAdministrators() throws Exception {
		mvc.perform(get("/api/v1/reports/pass-sales").param("from", "2025-03-01")
			.param("to", "2025-03-31")
			.header("Authorization", "Bearer " + TRAINER_TOKEN)).andExpect(status().isForbidden());
	}

	/**
	 * February 2025: three Pole Dance passes (two bought, one assigned at a discount), an Open pass
	 * given for free, and passes that do not count; one single entry in January and one paid just
	 * after midnight on 1 March in Warsaw.
	 */
	private final class Sales {

		Sales() {
			bought(poleFour, "180.00", "ACTIVE", at("2025-02-03T09:00:00Z"));
			bought(poleFour, "180.00", "EXPIRED", at("2025-02-10T08:00:00Z"));
			assigned(poleEight, "250.00", at("2025-02-10T16:00:00Z"));
			assigned(open, "0.00", at("2025-02-20T12:00:00Z"));
			bought(single, "55.00", "ACTIVE", at("2025-02-28T23:30:00Z"));
			bought(single, "55.00", "EXPIRED", at("2025-01-20T10:00:00Z"));
			// Waiting for the payment: no activation yet.
			pass(poleFour, "180.00", "PENDING_PAYMENT", order("180.00", poleFour), null);
			// Assigned by mistake and cancelled.
			pass(poleEight, "260.00", "CANCELLED", null, at("2025-02-12T10:00:00Z"));
		}

		private void bought(UUID variant, String price, String status, Instant paidAt) {
			UUID item = order(price, variant);
			jdbc.sql("UPDATE customer_order SET status = 'PAID', paid_at = :paidAt WHERE id = "
					+ "(SELECT order_id FROM order_item WHERE id = :item)")
				.param("paidAt", OffsetDateTime.ofInstant(paidAt, ZoneOffset.UTC))
				.param("item", item)
				.update();
			pass(variant, price, status, item, paidAt);
		}

		private void assigned(UUID variant, String price, Instant assignedAt) {
			pass(variant, price, "ACTIVE", null, assignedAt);
		}

	}

	private UUID order(String price, UUID variant) {
		UUID order = jdbc.sql("""
				INSERT INTO customer_order (buyer_id, total_amount) VALUES (:buyer, :price) RETURNING id
				""").param("buyer", client).param("price", new BigDecimal(price)).query(UUID.class).single();
		return jdbc.sql("""
				INSERT INTO order_item (order_id, variant_id, beneficiary_id, product_name, unit_price)
				VALUES (:order, :variant, :client, 'Karnet', :price)
				RETURNING id
				""")
			.param("order", order)
			.param("variant", variant)
			.param("client", client)
			.param("price", new BigDecimal(price))
			.query(UUID.class)
			.single();
	}

	private void pass(UUID variant, String price, String status, @Nullable UUID orderItem, @Nullable Instant activatedAt) {
		UUID pass = jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, order_item_id, product_name, price_paid, status)
				VALUES (:client, :variant, :item, 'Karnet', :price, :status)
				RETURNING id
				""")
			.param("client", client)
			.param("variant", variant)
			.param("item", orderItem)
			.param("price", new BigDecimal(price))
			.param("status", status)
			.query(UUID.class)
			.single();
		if (activatedAt != null) {
			jdbc.sql("""
					INSERT INTO pass_ledger (pass_id, kind, actor_account_id, occurred_at)
					VALUES (:pass, 'ACTIVATION', :actor, :at)
					""")
				.param("pass", pass)
				.param("actor", adminAccount)
				.param("at", OffsetDateTime.ofInstant(activatedAt, ZoneOffset.UTC))
				.update();
		}
	}

	private ResultActions report(String from, String to) throws Exception {
		return mvc.perform(get("/api/v1/reports/pass-sales").param("from", from)
			.param("to", to)
			.header("Authorization", "Bearer " + ADMIN_TOKEN));
	}

	private static Instant at(String instant) {
		return Instant.parse(instant);
	}

	private UUID insertProduct(String name, String kind) {
		UUID id = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES (:name, :kind) RETURNING id")
			.param("name", name)
			.param("kind", kind)
			.query(UUID.class)
			.single();
		products.add(id);
		return id;
	}

	private UUID insertVariant(UUID product, @Nullable Integer entries, int validityDays, String price) {
		return jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, :entries, :validity, :price)
				RETURNING id
				""")
			.param("product", product)
			.param("entries", entries)
			.param("validity", validityDays)
			.param("price", new BigDecimal(price))
			.query(UUID.class)
			.single();
	}

	private UUID addAccount(String email, String roleCode) {
		UUID id = jdbc.sql("""
				INSERT INTO account (email, status, activated_at) VALUES (:email, 'ACTIVE', now()) RETURNING id
				""").param("email", email).query(UUID.class).single();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id)
			.param("code", roleCode)
			.update();
		return id;
	}

	private void signIn(UUID account, String firebaseUid, String token) {
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", account)
			.param("uid", firebaseUid)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
	}

}
