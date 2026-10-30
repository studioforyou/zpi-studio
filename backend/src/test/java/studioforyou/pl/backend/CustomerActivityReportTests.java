package studioforyou.pl.backend;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
 * Administrators count active and new customers in a period (#163). The data lies in March 2025 and
 * the 31 days before it, away from the current dates other tests use. Without a studio row the
 * studio's time zone is Europe/Warsaw (UTC+1 in March until the 30th, then UTC+2).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerActivityReportTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminAccount;

	private UUID variant;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "ADMIN", OffsetDateTime.parse("2024-01-01T10:00:00Z"));
		signIn(adminAccount, "uid-admin", ADMIN_TOKEN);
		signIn(addAccount("trainer@example.com", "TRAINER", OffsetDateTime.parse("2024-01-01T10:00:00Z")),
				"uid-trainer", TRAINER_TOKEN);
		UUID product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('Karnet', 'ENTRY_PASS', 'ADULT') RETURNING id
				""").query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 260.00) RETURNING id
				""").param("product", product).query(UUID.class).single();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM reservation").update();
		jdbc.sql("DELETE FROM schedule_event").update();
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
	void countsNewCustomersByAccountActivationAndProfilesWithoutAccount() throws Exception {
		addClient("march@example.com", OffsetDateTime.parse("2025-03-10T12:00:00Z"));
		// 23:30 on 31 March in Warsaw still belongs to March; 00:30 on 1 April does not.
		addClient("late@example.com", OffsetDateTime.parse("2025-03-31T21:30:00Z"));
		addClient("april@example.com", OffsetDateTime.parse("2025-03-31T22:30:00Z"));
		addClient("february@example.com", OffsetDateTime.parse("2025-02-15T12:00:00Z"));
		addClient("invited@example.com", null);
		addAccount("new-trainer@example.com", "TRAINER", OffsetDateTime.parse("2025-03-12T12:00:00Z"));
		insertProfile(null, "CHILD", OffsetDateTime.parse("2025-03-20T12:00:00Z"));

		report("2025-03-01", "2025-03-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.current.from").value("2025-03-01"))
			.andExpect(jsonPath("$.current.to").value("2025-03-31"))
			.andExpect(jsonPath("$.current.newCustomers").value(3))
			.andExpect(jsonPath("$.previous.from").value("2025-01-29"))
			.andExpect(jsonPath("$.previous.to").value("2025-02-28"))
			.andExpect(jsonPath("$.previous.newCustomers").value(1));
	}

	@Test
	void countsCustomersWithAValidPassOrAReservationOnce() throws Exception {
		UUID passAcrossPeriods = insertProfile(null, "ADULT", OffsetDateTime.parse("2024-06-01T12:00:00Z"));
		insertPass(passAcrossPeriods, "EXPIRED", "2025-02-20", "2025-03-02");
		insertPass(insertProfile(null, "ADULT", OffsetDateTime.parse("2024-06-01T12:00:00Z")), "CANCELLED",
				"2025-03-05", "2025-04-04");
		insertPass(insertProfile(null, "ADULT", OffsetDateTime.parse("2024-06-01T12:00:00Z")), "PENDING_PAYMENT",
				null, null);
		insertPass(insertProfile(null, "ADULT", OffsetDateTime.parse("2024-06-01T12:00:00Z")), "EXPIRED",
				"2025-01-01", "2025-01-28");

		UUID march15 = insertEvent("2025-03-15T17:00:00Z", "SCHEDULED");
		UUID cancelledEvent = insertEvent("2025-03-16T17:00:00Z", "CANCELLED");
		insertReservation(march15, passAcrossPeriods, "ACTIVE");
		insertReservation(march15, insertProfile(null, "CHILD", OffsetDateTime.parse("2024-06-01T12:00:00Z")),
				"ACTIVE");
		insertReservation(march15, insertProfile(null, "ADULT", OffsetDateTime.parse("2024-06-01T12:00:00Z")),
				"CANCELLED");
		insertReservation(cancelledEvent, insertProfile(null, "ADULT", OffsetDateTime.parse("2024-06-01T12:00:00Z")),
				"ACTIVE");

		report("2025-03-01", "2025-03-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.current.activeCustomers").value(2))
			.andExpect(jsonPath("$.current.newCustomers").value(0))
			.andExpect(jsonPath("$.previous.activeCustomers").value(1));
	}

	@Test
	void rejectsAPeriodEndingBeforeItStarts() throws Exception {
		report("2025-03-31", "2025-03-01").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("to"));
	}

	@Test
	void rejectsAPeriodLongerThanTwoYears() throws Exception {
		report("2023-01-01", "2024-12-31").andExpect(status().isOk());
		report("2023-01-01", "2025-01-01").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("to"));
	}

	@Test
	void isOnlyForAdministrators() throws Exception {
		mvc.perform(get("/api/v1/reports/customer-activity").param("from", "2025-03-01")
			.param("to", "2025-03-31")
			.header("Authorization", "Bearer " + TRAINER_TOKEN)).andExpect(status().isForbidden());
	}

	private ResultActions report(String from, String to) throws Exception {
		return mvc.perform(get("/api/v1/reports/customer-activity").param("from", from)
			.param("to", to)
			.header("Authorization", "Bearer " + ADMIN_TOKEN));
	}

	private UUID addClient(String email, @Nullable OffsetDateTime activatedAt) {
		UUID account = addAccount(email, "CLIENT", activatedAt);
		insertProfile(account, "ADULT", OffsetDateTime.parse("2024-06-01T12:00:00Z"));
		return account;
	}

	private UUID addAccount(String email, String roleCode, @Nullable OffsetDateTime activatedAt) {
		UUID id = jdbc.sql("""
				INSERT INTO account (email, status, activated_at) VALUES (:email, :status, :activatedAt) RETURNING id
				""")
			.param("email", email)
			.param("status", activatedAt == null ? "INVITED" : "ACTIVE")
			.param("activatedAt", activatedAt)
			.query(UUID.class)
			.single();
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

	private UUID insertProfile(@Nullable UUID account, String kind, OffsetDateTime createdAt) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name, created_at)
				VALUES (:account, :kind, 'Jan', 'Testowy', :createdAt)
				RETURNING id
				""")
			.param("account", account)
			.param("kind", kind)
			.param("createdAt", createdAt)
			.query(UUID.class)
			.single();
	}

	private void insertPass(UUID customer, String status, @Nullable String validFrom, @Nullable String validUntil) {
		jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, price_paid, status, valid_from,
				                           valid_until)
				VALUES (:customer, :variant, 'Karnet', 260.00, :status, CAST(:validFrom AS DATE),
				        CAST(:validUntil AS DATE))
				""")
			.param("customer", customer)
			.param("variant", variant)
			.param("status", status)
			.param("validFrom", validFrom)
			.param("validUntil", validUntil)
			.update();
	}

	private UUID insertEvent(String startsAt, String status) {
		OffsetDateTime start = OffsetDateTime.parse(startsAt);
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, starts_at, ends_at, status)
				VALUES ('CLASS', :startsAt, :endsAt, :status)
				RETURNING id
				""")
			.param("startsAt", start)
			.param("endsAt", start.plusHours(1))
			.param("status", status)
			.query(UUID.class)
			.single();
	}

	private void insertReservation(UUID event, UUID customer, String status) {
		jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status)
				VALUES (:event, :customer, :bookedBy, :status)
				""")
			.param("event", event)
			.param("customer", customer)
			.param("bookedBy", adminAccount)
			.param("status", status)
			.update();
	}

}
