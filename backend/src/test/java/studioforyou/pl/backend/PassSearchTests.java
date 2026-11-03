package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
 * Administrators search passes of all participants matching a name, phone number or e-mail
 * address, and see each holder's e-mail address. Set up as in {@link CustomerPassTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PassSearchTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	private static final String CLIENT_TOKEN = "client-token";

	private static final ZoneId STUDIO_ZONE = ZoneId.of("Europe/Warsaw");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminAccount;

	private UUID annaAdamek;

	private UUID jan;

	private UUID janChild;

	private UUID variant;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "uid-admin", "ADMIN", ADMIN_TOKEN);
		addAccount("trainer@example.com", "uid-trainer", "TRAINER", TRAINER_TOKEN);
		UUID annaAccount = addAccount("anna.first@example.com", "uid-anna", "CLIENT", CLIENT_TOKEN);
		UUID otherAnnaAccount = addAccount("anna.second@example.com", "uid-anna-2", "CLIENT", "anna-2-token");
		UUID janAccount = addAccount("jan.nowak@example.com", "uid-jan", "CLIENT", "jan-token");
		annaAdamek = insertCustomer(annaAccount, "ADULT", "Anna", "Adamek", "ACTIVE");
		insertCustomer(otherAnnaAccount, "ADULT", "Anna", "Adamek", "ACTIVE");
		jan = insertCustomer(janAccount, "ADULT", "Jan", "Nowak", "ACTIVE");
		janChild = insertCustomer(null, "CHILD", "Ola", "Nowak", "ACTIVE");
		insertCustomer(null, "ADULT", "Piotr", "Adamek", "INACTIVE");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", jan)
			.param("child", janChild)
			.update();
		UUID product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('Karnet Open', 'ENTRY_PASS', 'ANY') RETURNING id
				""").query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 260.00) RETURNING id
				""").param("product", product).query(UUID.class).single();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM pass_ledger").update();
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
	void adminFindsThePassesOfEveryParticipantWithTheName() throws Exception {
		String older = assign(annaAdamek);
		String newer = assign(annaAdamek);
		UUID otherAnna = jdbc.sql("SELECT id FROM customer_profile WHERE first_name = 'Anna' AND id <> :id")
			.param("id", annaAdamek)
			.query(UUID.class)
			.single();
		String otherAnnasPass = assign(otherAnna);
		assign(jan);

		String body = perform(ADMIN_TOKEN, get("/api/v1/customer-passes?query=adamek"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(3)))
			.andExpect(jsonPath("$.totalItems").value(3))
			.andExpect(jsonPath("$.items[*].holder.displayName", everyItem(is("Anna Adamek"))))
			.andExpect(jsonPath("$.items[*].holder.email",
					containsInAnyOrder("anna.first@example.com", "anna.first@example.com", "anna.second@example.com")))
			.andExpect(jsonPath("$.items[0].pass.productName").value("Karnet Open"))
			.andExpect(jsonPath("$.items[0].pass.status").value("ACTIVE"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		java.util.List<String> ids = JsonPath.read(body, "$.items[*].pass.id");
		// Grouped by participant, newest first within a participant.
		if (ids.get(0).equals(otherAnnasPass)) {
			assertThat(ids).containsExactly(otherAnnasPass, newer, older);
		}
		else {
			assertThat(ids).containsExactly(newer, older, otherAnnasPass);
		}
	}

	@Test
	void aChildIsFoundByAndShownWithItsGuardiansEmail() throws Exception {
		assign(janChild);

		perform(ADMIN_TOKEN, get("/api/v1/customer-passes?query=jan.nowak@"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].holder.id").value(janChild.toString()))
			.andExpect(jsonPath("$.items[0].holder.kind").value("CHILD"))
			.andExpect(jsonPath("$.items[0].holder.email").value("jan.nowak@example.com"))
			.andExpect(jsonPath("$.items[0].holder.emailOfGuardian").value(true));
	}

	@Test
	void theSearchIsPagedAndSkipsParticipantsWithoutMatchingPasses() throws Exception {
		assign(annaAdamek);
		assign(annaAdamek);
		assign(jan);

		perform(ADMIN_TOKEN, get("/api/v1/customer-passes?query=adamek&size=1&page=1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.totalPages").value(2));
		perform(ADMIN_TOKEN, get("/api/v1/customer-passes?query=piotr"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)));
		perform(ADMIN_TOKEN, get("/api/v1/customer-passes").param("query", " "))
			.andExpect(status().isBadRequest());
		perform(CLIENT_TOKEN, get("/api/v1/customer-passes?query=adamek"))
			.andExpect(status().isForbidden());
	}

	@Test
	void onlyAdministratorsSearchParticipantsByEmailAndSeeIt() throws Exception {
		perform(ADMIN_TOKEN, get("/api/v1/customers?query=anna.second"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].email").value("anna.second@example.com"))
			.andExpect(jsonPath("$.items[0].emailOfGuardian").value(false));
		perform(ADMIN_TOKEN, get("/api/v1/customers?query=ola"))
			.andExpect(jsonPath("$.items[0].email").value("jan.nowak@example.com"))
			.andExpect(jsonPath("$.items[0].emailOfGuardian").value(true));

		perform(TRAINER_TOKEN, get("/api/v1/customers?query=anna.second"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)));
		perform(TRAINER_TOKEN, get("/api/v1/customers?query=adamek"))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].email").isEmpty());
	}

	private String assign(UUID customer) throws Exception {
		String body = perform(ADMIN_TOKEN, post("/api/v1/customers/" + customer + "/passes"),
				"{ \"variantId\": \"%s\", \"validFrom\": \"%s\" }".formatted(variant, LocalDate.now(STUDIO_ZONE)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
		return perform(token, request, null);
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

	private UUID insertCustomer(UUID account, String kind, String firstName, String lastName, String status) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name, status)
				VALUES (:account, :kind, :firstName, :lastName, :status)
				RETURNING id
				""")
			.param("account", account, java.sql.Types.OTHER)
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

}
