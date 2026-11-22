package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

import studioforyou.pl.backend.membership.PersonalTrainingAccounting;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.Effect;

/**
 * Make-up policies: administrators define them and link them to pass products. A change applies
 * only to credits granted afterwards. Not transactional; the data is removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MakeupPolicyTests {

	private static final String ADMIN_TOKEN = "policy-admin-token";

	private static final String CLIENT_TOKEN = "policy-client-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private PersonalTrainingAccounting accounting;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminId;

	private UUID service;

	@BeforeEach
	void setUp() {
		adminId = addAccount("policy-admin@example.com", "uid-policy-admin", "ADMIN", ADMIN_TOKEN);
		addAccount("policy-client@example.com", "uid-policy-client", "CLIENT", CLIENT_TOKEN);
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes) VALUES ('policy-test', 'GROUP_CLASS', 60)
				RETURNING id
				""").query(UUID.class).single();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM makeup_credit WHERE customer_id IN (SELECT id FROM customer_profile WHERE first_name = 'policy-test')").update();
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE customer_id IN (SELECT id FROM customer_profile WHERE first_name = 'policy-test'))").update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :s)").param("s", service).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :s").param("s", service).update();
		jdbc.sql("DELETE FROM customer_pass WHERE customer_id IN (SELECT id FROM customer_profile WHERE first_name = 'policy-test')").update();
		jdbc.sql("DELETE FROM customer_profile WHERE first_name = 'policy-test'").update();
		jdbc.sql("DELETE FROM pass_product_service WHERE service_type_id = :s").param("s", service).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE product_id IN (SELECT id FROM pass_product WHERE name LIKE 'policy-test%')").update();
		jdbc.sql("DELETE FROM pass_product WHERE name LIKE 'policy-test%'").update();
		jdbc.sql("DELETE FROM makeup_policy WHERE name LIKE 'policy-test%'").update();
		jdbc.sql("DELETE FROM service_type WHERE id = :s").param("s", service).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (SELECT id FROM account WHERE email LIKE 'policy-%@example.com')").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (SELECT id FROM account WHERE email LIKE 'policy-%@example.com')").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE 'policy-%@example.com'").update();
	}

	@Test
	void adminCreatesChangesAndDeactivatesAPolicy() throws Exception {
		String id = JsonPath.read(perform(ADMIN_TOKEN, post("/api/v1/makeup-policies"), policyJson("policy-test 30", 30, 2))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/v1/makeup-policies/")))
			.andExpect(jsonPath("$.name").value("policy-test 30"))
			.andExpect(jsonPath("$.validityDays").value(30))
			.andExpect(jsonPath("$.maxPerPass").value(2))
			.andExpect(jsonPath("$.grantOnInTimeCancel").value(true))
			.andExpect(jsonPath("$.grantOnStudioCancel").value(false))
			.andExpect(jsonPath("$.expiryCheckedAgainst").value("EVENT_DATE"))
			.andExpect(jsonPath("$.isActive").value(true))
			.andExpect(jsonPath("$.productCount").value(0))
			.andReturn().getResponse().getContentAsString(), "$.id");
		createProduct("policy-test product", id);

		perform(ADMIN_TOKEN, put("/api/v1/makeup-policies/" + id), """
				{ "name": "  policy-test 60  ", "validityDays": 60, "maxPerPass": null, "grantOnInTimeCancel": false,
				  "grantOnStudioCancel": true, "expiryCheckedAgainst": "BOOK_DATE" }
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("policy-test 60"))
			.andExpect(jsonPath("$.validityDays").value(60))
			.andExpect(jsonPath("$.maxPerPass").isEmpty())
			.andExpect(jsonPath("$.grantOnInTimeCancel").value(false))
			.andExpect(jsonPath("$.expiryCheckedAgainst").value("BOOK_DATE"))
			.andExpect(jsonPath("$.productCount").value(1));

		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies/" + id + "/deactivate"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isActive").value(false));
		perform(ADMIN_TOKEN, get("/api/v1/makeup-policies?active=true"), null)
			.andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").isEmpty());
		perform(ADMIN_TOKEN, get("/api/v1/makeup-policies?active=false"), null)
			.andExpect(jsonPath("$.items[?(@.id == '" + id + "')].productCount").value(1));
		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies/" + id + "/activate"), null)
			.andExpect(jsonPath("$.isActive").value(true));
		perform(ADMIN_TOKEN, get("/api/v1/makeup-policies/" + UUID.randomUUID()), null)
			.andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT count(*) FROM makeup_policy WHERE id = CAST(:id AS UUID)").param("id", id)
			.query(Long.class).single()).isOne();
	}

	@Test
	void onlyAnAdminManagesPolicies() throws Exception {
		perform(CLIENT_TOKEN, get("/api/v1/makeup-policies"), null)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		perform(CLIENT_TOKEN, post("/api/v1/makeup-policies"), policyJson("policy-test", 30, null))
			.andExpect(status().isForbidden());
		String id = createPolicy("policy-test", 30);
		perform(CLIENT_TOKEN, put("/api/v1/makeup-policies/" + id), policyJson("policy-test x", 10, null))
			.andExpect(status().isForbidden());
		perform(CLIENT_TOKEN, post("/api/v1/makeup-policies/" + id + "/deactivate"), null)
			.andExpect(status().isForbidden());
	}

	@Test
	void invalidPoliciesAreRejected() throws Exception {
		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies"), """
				{ "name": " ", "validityDays": 0, "maxPerPass": 0, "grantOnInTimeCancel": true,
				  "grantOnStudioCancel": true, "expiryCheckedAgainst": "EVENT_DATE" }
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty())
			.andExpect(jsonPath("$.errors[?(@.field == 'validityDays')]").isNotEmpty())
			.andExpect(jsonPath("$.errors[?(@.field == 'maxPerPass')]").isNotEmpty());
		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies"), policyJson("x".repeat(121), 30, null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies"), """
				{ "name": "policy-test", "validityDays": 30, "grantOnInTimeCancel": true,
				  "grantOnStudioCancel": true, "expiryCheckedAgainst": "SOMETIME" }
				""")
			.andExpect(status().isBadRequest());
	}

	@Test
	void productsLinkOnlyActivePoliciesAndCanStopGrantingMakeups() throws Exception {
		String active = createPolicy("policy-test active", 30);
		String inactive = createPolicy("policy-test inactive", 14);
		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies/" + inactive + "/deactivate"), null).andExpect(status().isOk());

		String product = createProduct("policy-test product", active);
		perform(ADMIN_TOKEN, get("/api/v1/pass-products/" + product), null)
			.andExpect(jsonPath("$.makeupPolicyId").value(active))
			.andExpect(jsonPath("$.makeupPolicyName").value("policy-test active"));
		perform(ADMIN_TOKEN, get("/api/v1/pass-products"), null)
			.andExpect(jsonPath("$.items[?(@.id == '" + product + "')].makeupPolicyName").value("policy-test active"));

		perform(ADMIN_TOKEN, post("/api/v1/pass-products"), productJson("policy-test other", inactive))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("MAKEUP_POLICY_UNAVAILABLE"));
		perform(ADMIN_TOKEN, patch("/api/v1/pass-products/" + product), "{ \"makeupPolicyId\": \"%s\" }".formatted(inactive))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("MAKEUP_POLICY_UNAVAILABLE"));

		// The policy already linked may stay when it is deactivated later.
		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies/" + active + "/deactivate"), null).andExpect(status().isOk());
		perform(ADMIN_TOKEN, patch("/api/v1/pass-products/" + product),
				"{ \"name\": \"policy-test renamed\", \"makeupPolicyId\": \"%s\" }".formatted(active))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.makeupPolicyId").value(active));

		perform(ADMIN_TOKEN, patch("/api/v1/pass-products/" + product), "{ \"removeMakeupPolicy\": true }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.makeupPolicyId").isEmpty())
			.andExpect(jsonPath("$.makeupPolicyName").isEmpty());
		String withoutMakeups = createProduct("policy-test none", null);
		perform(ADMIN_TOKEN, get("/api/v1/pass-products/" + withoutMakeups), null)
			.andExpect(jsonPath("$.makeupPolicyId").isEmpty());
	}

	@Test
	void aChangedPolicyAppliesOnlyToCreditsGrantedAfterwards() throws Exception {
		String policy = createPolicy("policy-test grant", 30);
		String product = createProduct("policy-test grant", policy);
		UUID variant = jdbc.sql("SELECT id FROM pass_product_variant WHERE product_id = CAST(:id AS UUID)")
			.param("id", product).query(UUID.class).single();
		UUID customer = jdbc.sql("INSERT INTO customer_profile (kind, first_name, last_name) VALUES ('ADULT', 'policy-test', 'Client') RETURNING id")
			.query(UUID.class).single();
		UUID pass = jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining, price_paid,
				                           status, valid_from, valid_until)
				VALUES (:customer, :variant, 'policy-test grant', 8, 7, 100, 'ACTIVE', CURRENT_DATE - 1, CURRENT_DATE + 29)
				RETURNING id
				""").param("customer", customer).param("variant", variant).query(UUID.class).single();
		Instant grantedAt = Instant.now().minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = grantedAt.plus(Duration.ofDays(30));
		UUID credit = jdbc.sql("""
				INSERT INTO makeup_credit (customer_id, source_pass_id, granted_at, expires_at)
				VALUES (:customer, :pass, :granted, :expires) RETURNING id
				""").param("customer", customer).param("pass", pass)
			.param("granted", grantedAt.atOffset(ZoneOffset.UTC)).param("expires", expiresAt.atOffset(ZoneOffset.UTC))
			.query(UUID.class).single();
		UUID event = jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, starts_at, ends_at)
				VALUES ('CLASS', :service, now() + interval '3 days', now() + interval '3 days 1 hour') RETURNING id
				""").param("service", service).query(UUID.class).single();
		UUID reservation = jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, customer_pass_id) VALUES (:event, :customer, :admin, :pass)
				RETURNING id
				""").param("event", event).param("customer", customer).param("admin", adminId).param("pass", pass)
			.query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_ledger (pass_id, kind, entries_delta, reservation_id) VALUES (:pass, 'ENTRY_RESERVED', -1, :reservation)")
			.param("pass", pass).param("reservation", reservation).update();

		assertThat(accounting.preview(customer, pass, null, reservation, true, Instant.now()).validityDays()).isEqualTo(30);
		perform(ADMIN_TOKEN, put("/api/v1/makeup-policies/" + policy), policyJson("policy-test grant", 60, 2))
			.andExpect(status().isOk());

		var quote = accounting.preview(customer, pass, null, reservation, true, Instant.now());
		assertThat(quote.effect()).isEqualTo(Effect.MAKEUP_GRANTED);
		assertThat(quote.validityDays()).isEqualTo(60);
		assertThat(jdbc.sql("SELECT expires_at FROM makeup_credit WHERE id = :id").param("id", credit)
			.query(OffsetDateTime.class).single().toInstant()).isEqualTo(expiresAt);

		perform(ADMIN_TOKEN, post("/api/v1/makeup-policies/" + policy + "/deactivate"), null).andExpect(status().isOk());
		assertThat(accounting.preview(customer, pass, null, reservation, true, Instant.now()).effect())
			.isEqualTo(Effect.ENTRY_FORFEITED);
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single())
			.isEqualTo("AVAILABLE");
	}

	private String createPolicy(String name, int days) throws Exception {
		String body = perform(ADMIN_TOKEN, post("/api/v1/makeup-policies"), policyJson(name, days, null))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String createProduct(String name, String policyId) throws Exception {
		String body = perform(ADMIN_TOKEN, post("/api/v1/pass-products"), productJson(name, policyId))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String productJson(String name, String policyId) {
		return """
				{ "name": "%s", "kind": "ENTRY_PASS", "audience": "ADULT", "serviceTypeIds": ["%s"], "makeupPolicyId": %s,
				  "variants": [ { "entriesCount": 8, "validityDays": 30, "price": { "amountMinor": 26000, "currency": "PLN" } } ] }
				""".formatted(name, service, policyId == null ? "null" : "\"" + policyId + "\"");
	}

	private static String policyJson(String name, int days, Integer maxPerPass) {
		return """
				{ "name": "%s", "validityDays": %d, "maxPerPass": %s, "grantOnInTimeCancel": true,
				  "grantOnStudioCancel": false, "expiryCheckedAgainst": "EVENT_DATE" }
				""".formatted(name, days, maxPerPass);
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
			.param("email", email).query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", firebaseUid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id).param("code", roleCode).update();
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build());
		return id;
	}

}
