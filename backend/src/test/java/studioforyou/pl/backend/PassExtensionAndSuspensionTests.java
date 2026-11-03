package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Administrators extend passes and suspend them when the product allows it. Set up as in
 * {@link CustomerPassTests}: Firebase ID tokens are mocked at the {@link JwtDecoder}, and the
 * data is removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PassExtensionAndSuspensionTests {

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

	private UUID suspendable;

	private UUID notSuspendable;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "uid-admin", "ADMIN", ADMIN_TOKEN);
		addAccount("client@example.com", "uid-client", "CLIENT", CLIENT_TOKEN);
		adult = insertCustomer("ADULT", "Katarzyna", "Zielińska", "ACTIVE");
		suspendable = insertVariant(insertProduct("Karnet Open", true, (short) 10), 8, 30, "260.00");
		notSuspendable = insertVariant(insertProduct("Karnet Pole Dance", false, null), 8, 30, "260.00");
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM pass_suspension").update();
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
	void adminExtendsAPassAndTheHistoryKeepsTheChange() throws Exception {
		UUID pass = assign(notSuspendable, today());
		LocalDate before = today().plusDays(29);
		LocalDate after = today().plusDays(45);

		perform(ADMIN_TOKEN, post(passPath(pass) + "/extend"), """
				{ "validUntil": "%s", "reason": "Kontuzja", "version": 0 }
				""".formatted(after))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.validUntil").value(after.toString()))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.version").value(1));

		Map<String, Object> extension = jdbc.sql("""
				SELECT entries_delta, valid_until_before, valid_until_after, reason, actor_account_id
				FROM pass_ledger WHERE pass_id = :id AND kind = 'EXTENSION'
				""")
			.param("id", pass)
			.query()
			.singleRow();
		assertThat(extension).containsEntry("entries_delta", 0)
			.containsEntry("valid_until_before", java.sql.Date.valueOf(before))
			.containsEntry("valid_until_after", java.sql.Date.valueOf(after))
			.containsEntry("reason", "Kontuzja")
			.containsEntry("actor_account_id", adminAccount);
	}

	@Test
	void anExpiredPassCanBeExtendedAndBecomesUsableAgain() throws Exception {
		UUID pass = insertPass(notSuspendable, "EXPIRED", today().minusDays(40), today().minusDays(11));

		perform(ADMIN_TOKEN, post(passPath(pass) + "/extend"), extendJson(today().minusDays(1), 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("validUntil"))
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/extend"), extendJson(today().plusDays(7), 0))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.validUntil").value(today().plusDays(7).toString()));
		assertThat(jdbc.sql("SELECT status FROM customer_pass").query(String.class).single()).isEqualTo("ACTIVE");
	}

	@Test
	void anExtensionMustMoveTheLastDayLaterOnTheCurrentVersion() throws Exception {
		UUID pass = assign(notSuspendable, today());
		perform(ADMIN_TOKEN, post(passPath(pass) + "/extend"), extendJson(today().plusDays(29), 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/extend"), extendJson(today().plusDays(40), 3))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/extend"), "{}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[?(@.field == 'validUntil')].code").value("REQUIRED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'version')].code").value("REQUIRED"));

		jdbc.sql("UPDATE customer_pass SET status = 'CANCELLED'").update();
		perform(ADMIN_TOKEN, post(passPath(pass) + "/extend"), extendJson(today().plusDays(40), 0))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_NOT_USABLE"));
		perform(ADMIN_TOKEN, post(passesOf(adult) + "/" + UUID.randomUUID() + "/extend"),
				extendJson(today().plusDays(40), 0))
			.andExpect(status().isNotFound());
	}

	@Test
	void adminSuspendsAPassAndItsLastDayMoves() throws Exception {
		UUID pass = assign(suspendable, today());

		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"), suspendJson(today(), today().plusDays(6), 0))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"))
			.andExpect(jsonPath("$.validUntil").value(today().plusDays(29 + 7).toString()))
			.andExpect(jsonPath("$.suspensions", hasSize(1)))
			.andExpect(jsonPath("$.suspensions[0].startsOn").value(today().toString()))
			.andExpect(jsonPath("$.suspensions[0].endsOn").value(today().plusDays(6).toString()))
			.andExpect(jsonPath("$.suspensions[0].reason").value("Wyjazd"))
			.andExpect(jsonPath("$.suspensions[0].createdBy").value(adminAccount.toString()));

		perform(ADMIN_TOKEN, get(passesOf(adult)), null)
			.andExpect(jsonPath("$.items[0].status").value("SUSPENDED"))
			.andExpect(jsonPath("$.items[0].suspensions", hasSize(1)));
		Map<String, Object> extension = jdbc.sql("""
				SELECT valid_until_before, valid_until_after FROM pass_ledger
				WHERE pass_id = :id AND kind = 'EXTENSION'
				""")
			.param("id", pass)
			.query()
			.singleRow();
		assertThat(extension).containsEntry("valid_until_before", java.sql.Date.valueOf(today().plusDays(29)))
			.containsEntry("valid_until_after", java.sql.Date.valueOf(today().plusDays(36)));
	}

	@Test
	void aSuspensionLaterOnKeepsThePassActiveUntilItStarts() throws Exception {
		UUID pass = assign(suspendable, today());
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"),
				suspendJson(today().plusDays(10), today().plusDays(12), 0))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.validUntil").value(today().plusDays(32).toString()));
	}

	@Test
	void aSuspensionMustFollowTheProductsRules() throws Exception {
		UUID other = assign(notSuspendable, today());
		perform(ADMIN_TOKEN, post(passPath(other) + "/suspend"), suspendJson(today(), today().plusDays(2), 0))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_SUSPENSION_NOT_ALLOWED"));

		UUID pass = assign(suspendable, today());
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"),
				suspendJson(today().minusDays(1), today().plusDays(2), 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startsOn"));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"), suspendJson(today().plusDays(3), today(), 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endsOn"));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"),
				suspendJson(today().plusDays(25), today().plusDays(30), 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endsOn"));

		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"), suspendJson(today(), today().plusDays(5), 0))
			.andExpect(status().isOk());
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"),
				suspendJson(today().plusDays(5), today().plusDays(6), 1))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_SUSPENSION_NOT_ALLOWED"));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"),
				suspendJson(today().plusDays(10), today().plusDays(14), 1))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_SUSPENSION_NOT_ALLOWED"))
			.andExpect(jsonPath("$.maxSuspensionDays").value(10))
			.andExpect(jsonPath("$.usedSuspensionDays").value(6));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"),
				suspendJson(today().plusDays(10), today().plusDays(13), 1))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.suspensions", hasSize(2)))
			.andExpect(jsonPath("$.validUntil").value(today().plusDays(29 + 10).toString()));
		assertThat(jdbc.sql("SELECT count(*) FROM pass_suspension").query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void aSuspensionThatHasNotStartedIsCancelledAsAWhole() throws Exception {
		UUID pass = assign(suspendable, today());
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"),
				suspendJson(today().plusDays(3), today().plusDays(5), 0))
			.andExpect(status().isOk());
		UUID suspension = jdbc.sql("SELECT id FROM pass_suspension").query(UUID.class).single();

		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspensions/" + suspension + "/cancel"), "{ \"version\": 1 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.validUntil").value(today().plusDays(29).toString()))
			.andExpect(jsonPath("$.suspensions", hasSize(0)))
			.andExpect(jsonPath("$.version").value(2));
		assertThat(jdbc.sql("SELECT cancelled_at IS NOT NULL FROM pass_suspension").query(Boolean.class).single())
			.isTrue();
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspensions/" + suspension + "/cancel"), "{ \"version\": 2 }")
			.andExpect(status().isNotFound());
	}

	@Test
	void aSuspensionInProgressEndsYesterday() throws Exception {
		UUID pass = insertPass(suspendable, "ACTIVE", today().minusDays(10), today().plusDays(24));
		UUID suspension = insertSuspension(pass, today().minusDays(2), today().plusDays(2));

		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspensions/" + suspension + "/cancel"), "{ \"version\": 0 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.validUntil").value(today().plusDays(21).toString()))
			.andExpect(jsonPath("$.suspensions[0].endsOn").value(today().minusDays(1).toString()));

		UUID ended = insertSuspension(pass, today().minusDays(8), today().minusDays(6));
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspensions/" + ended + "/cancel"), "{ \"version\": 1 }")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_SUSPENSION_NOT_ALLOWED"));
	}

	@Test
	void theParticipantSeesTheSuspendedStatus() throws Exception {
		UUID clientAccount = jdbc.sql("SELECT id FROM account WHERE email = 'client@example.com'")
			.query(UUID.class)
			.single();
		jdbc.sql("UPDATE customer_profile SET account_id = :account WHERE id = :id")
			.param("account", clientAccount)
			.param("id", adult)
			.update();
		UUID pass = assign(suspendable, today());
		perform(ADMIN_TOKEN, post(passPath(pass) + "/suspend"), suspendJson(today(), today(), 0))
			.andExpect(status().isOk());

		perform(CLIENT_TOKEN, get("/api/v1/me/profiles/" + adult + "/passes"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].status").value("SUSPENDED"));
	}

	@Test
	void onlyAnAdminCanExtendOrSuspend() throws Exception {
		UUID pass = assign(suspendable, today());
		perform(CLIENT_TOKEN, post(passPath(pass) + "/extend"), extendJson(today().plusDays(40), 0))
			.andExpect(status().isForbidden());
		perform(CLIENT_TOKEN, post(passPath(pass) + "/suspend"), suspendJson(today(), today(), 0))
			.andExpect(status().isForbidden());
		assertThat(jdbc.sql("SELECT version FROM customer_pass").query(Integer.class).single()).isZero();
	}

	private UUID assign(UUID variant, LocalDate validFrom) throws Exception {
		String body = perform(ADMIN_TOKEN, post(passesOf(adult)),
				"{ \"variantId\": \"%s\", \"validFrom\": \"%s\" }".formatted(variant, validFrom))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	private UUID insertPass(UUID variant, String status, LocalDate validFrom, LocalDate validUntil) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				                           price_paid, status, valid_from, valid_until, created_by)
				VALUES (:customer, :variant, 'Karnet', 8, 8, 260.00, :status, :validFrom, :validUntil, :admin)
				RETURNING id
				""")
			.param("customer", adult)
			.param("variant", variant)
			.param("status", status)
			.param("validFrom", validFrom)
			.param("validUntil", validUntil)
			.param("admin", adminAccount)
			.query(UUID.class)
			.single();
	}

	private UUID insertSuspension(UUID pass, LocalDate startsOn, LocalDate endsOn) {
		return jdbc.sql("""
				INSERT INTO pass_suspension (pass_id, starts_on, ends_on, created_by)
				VALUES (:pass, :startsOn, :endsOn, :admin)
				RETURNING id
				""")
			.param("pass", pass)
			.param("startsOn", startsOn)
			.param("endsOn", endsOn)
			.param("admin", adminAccount)
			.query(UUID.class)
			.single();
	}

	private String passPath(UUID pass) {
		return passesOf(adult) + "/" + pass;
	}

	private static String passesOf(UUID customer) {
		return "/api/v1/customers/" + customer + "/passes";
	}

	private static String extendJson(LocalDate validUntil, int version) {
		return "{ \"validUntil\": \"%s\", \"version\": %d }".formatted(validUntil, version);
	}

	private static String suspendJson(LocalDate startsOn, LocalDate endsOn, int version) {
		return "{ \"startsOn\": \"%s\", \"endsOn\": \"%s\", \"reason\": \"Wyjazd\", \"version\": %d }"
			.formatted(startsOn, endsOn, version);
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

	private UUID insertProduct(String name, boolean suspensionAllowed, Short maxSuspensionDays) {
		return jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience, suspension_allowed, max_suspension_days)
				VALUES (:name, 'ENTRY_PASS', 'ADULT', :allowed, :maxDays)
				RETURNING id
				""")
			.param("name", name)
			.param("allowed", suspensionAllowed)
			.param("maxDays", maxSuspensionDays, java.sql.Types.SMALLINT)
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
