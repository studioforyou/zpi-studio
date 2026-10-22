package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Pass products: administrators define them, everyone else only reads the active offer.
 * Firebase ID tokens are mocked at the {@link JwtDecoder}, as in the authentication tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PassProductTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String CLIENT_TOKEN = "client-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID poleDance;

	private UUID calisthenics;

	private UUID inactiveService;

	@BeforeEach
	void setUp() {
		addAccount("admin@example.com", "uid-admin", "ADMIN", ADMIN_TOKEN);
		addAccount("client@example.com", "uid-client", "CLIENT", CLIENT_TOKEN);
		poleDance = insertServiceType("Pole dance", true);
		calisthenics = insertServiceType("Calisthenics", true);
		inactiveService = insertServiceType("Yoga", false);
	}

	@Test
	void adminCreatesAProductWithVariantsAndServices() throws Exception {
		perform(ADMIN_TOKEN, post("/api/v1/pass-products"), productJson("Karnet Pole Dance", poleDance))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/v1/pass-products/")))
			.andExpect(jsonPath("$.name").value("Karnet Pole Dance"))
			.andExpect(jsonPath("$.kind").value("ENTRY_PASS"))
			.andExpect(jsonPath("$.audience").value("ADULT"))
			.andExpect(jsonPath("$.suspensionAllowed").value(true))
			.andExpect(jsonPath("$.maxSuspensionDays").value(14))
			.andExpect(jsonPath("$.isActive").value(true))
			.andExpect(jsonPath("$.serviceTypeIds[0]").value(poleDance.toString()))
			.andExpect(jsonPath("$.variants", hasSize(2)))
			.andExpect(jsonPath("$.variants[?(@.entriesCount == 8)].price.amountMinor").value(26000))
			.andExpect(jsonPath("$.variants[?(@.entriesCount == null)].validityDays").value(30));

		assertThat(jdbc.sql("SELECT price FROM pass_product_variant WHERE entries_count = 8")
			.query(BigDecimal.class)
			.single()).isEqualByComparingTo("260.00");
	}

	@Test
	void onlyAnAdminCanChangeTheOffer() throws Exception {
		perform(CLIENT_TOKEN, post("/api/v1/pass-products"), productJson("Karnet", poleDance))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		String id = createProduct("Karnet");
		perform(CLIENT_TOKEN, patch("/api/v1/pass-products/" + id), "{\"name\":\"Inny\"}")
			.andExpect(status().isForbidden());
		perform(CLIENT_TOKEN, post("/api/v1/pass-products/" + id + "/archive"), null)
			.andExpect(status().isForbidden());
	}

	@Test
	void clientsSeeOnlyActiveProductsAndVariants() throws Exception {
		String active = createProduct("Aktywny");
		String archived = createProduct("Archiwalny");
		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + archived + "/archive"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isActive").value(false));
		String variantId = firstVariantId(active);
		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + active + "/variants/" + variantId + "/deactivate"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isActive").value(false))
			.andExpect(jsonPath("$.validTo").isNotEmpty());

		perform(CLIENT_TOKEN, get("/api/v1/pass-products?active=false"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(active))
			.andExpect(jsonPath("$.items[0].variants", hasSize(1)));
		perform(CLIENT_TOKEN, get("/api/v1/pass-products/" + archived), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		perform(ADMIN_TOKEN, get("/api/v1/pass-products"), null)
			.andExpect(jsonPath("$.items", hasSize(2)));
		perform(ADMIN_TOKEN, get("/api/v1/pass-products?active=false"), null)
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(archived));
		perform(ADMIN_TOKEN, get("/api/v1/pass-products/" + active), null)
			.andExpect(jsonPath("$.variants", hasSize(2)));
	}

	@Test
	void adminUpdatesOnlyTheGivenFields() throws Exception {
		String id = createProduct("Karnet");

		perform(ADMIN_TOKEN, patch("/api/v1/pass-products/" + id), """
				{ "name": "Karnet Open", "suspensionAllowed": false,
				  "serviceTypeIds": ["%s", "%s"] }
				""".formatted(poleDance, calisthenics))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Karnet Open"))
			.andExpect(jsonPath("$.description").value("Opis"))
			.andExpect(jsonPath("$.audience").value("ADULT"))
			.andExpect(jsonPath("$.suspensionAllowed").value(false))
			.andExpect(jsonPath("$.maxSuspensionDays").isEmpty())
			.andExpect(jsonPath("$.serviceTypeIds", hasSize(2)));

		perform(ADMIN_TOKEN, patch("/api/v1/pass-products/" + id), "{ \"description\": \"\" }")
			.andExpect(jsonPath("$.description").isEmpty());
	}

	@Test
	void changingThePriceKeepsTheOldVariantInHistory() throws Exception {
		String id = createProduct("Karnet");
		String oldVariant = variantIdWithEntries(id, 8);

		String body = perform(ADMIN_TOKEN,
				post("/api/v1/pass-products/" + id + "/variants/" + oldVariant + "/change-price"),
				"{ \"price\": { \"amountMinor\": 28000, \"currency\": \"PLN\" } }")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entriesCount").value(8))
			.andExpect(jsonPath("$.validityDays").value(30))
			.andExpect(jsonPath("$.price.amountMinor").value(28000))
			.andExpect(jsonPath("$.isActive").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String newVariant = JsonPath.read(body, "$.id");
		assertThat(newVariant).isNotEqualTo(oldVariant);

		perform(ADMIN_TOKEN, get("/api/v1/pass-products/" + id + "/variants/" + oldVariant), null)
			.andExpect(jsonPath("$.isActive").value(false))
			.andExpect(jsonPath("$.price.amountMinor").value(26000));
		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + id + "/variants/" + oldVariant + "/change-price"),
				"{ \"price\": { \"amountMinor\": 30000, \"currency\": \"PLN\" } }")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_VARIANT_INACTIVE"));
	}

	@Test
	void anActiveVariantWithTheSameTermsIsRejected() throws Exception {
		String id = createProduct("Karnet");

		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + id + "/variants"),
				"{ \"entriesCount\": 8, \"validityDays\": 30, \"price\": { \"amountMinor\": 1000, \"currency\": \"PLN\" } }")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PASS_VARIANT_ALREADY_EXISTS"));
		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + id + "/variants"),
				"{ \"entriesCount\": 4, \"validityDays\": 30, \"price\": { \"amountMinor\": 18000, \"currency\": \"PLN\" } }")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.entriesCount").value(4));
	}

	@Test
	void archivedProductsGetNoNewVariants() throws Exception {
		String id = createProduct("Karnet");
		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + id + "/archive"), null).andExpect(status().isOk());

		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + id + "/variants"),
				"{ \"entriesCount\": 4, \"validityDays\": 30, \"price\": { \"amountMinor\": 18000, \"currency\": \"PLN\" } }")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_PRODUCT_ARCHIVED"));

		perform(ADMIN_TOKEN, post("/api/v1/pass-products/" + id + "/restore"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isActive").value(true));
	}

	@Test
	void inactiveServiceTypesCannotBeAdded() throws Exception {
		perform(ADMIN_TOKEN, post("/api/v1/pass-products"), productJson("Karnet", inactiveService))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_TYPE_UNAVAILABLE"));
		perform(ADMIN_TOKEN, post("/api/v1/pass-products"), productJson("Karnet", UUID.randomUUID()))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void invalidProductsAreRejected() throws Exception {
		perform(ADMIN_TOKEN, post("/api/v1/pass-products"), """
				{ "name": "", "kind": "ENTRY_PASS", "audience": "ADULT", "serviceTypeIds": [],
				  "variants": [ { "entriesCount": 0, "validityDays": 30,
				                  "price": { "amountMinor": -1, "currency": "pln" } } ] }
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'name')].code").value("REQUIRED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'variants[0].entriesCount')].code").value("OUT_OF_RANGE"))
			.andExpect(jsonPath("$.errors[?(@.field == 'variants[0].price.amountMinor')].code").value("OUT_OF_RANGE"))
			.andExpect(jsonPath("$.errors[?(@.field == 'variants[0].price.currency')].code").value("INVALID_FORMAT"));
		perform(ADMIN_TOKEN, post("/api/v1/pass-products"), """
				{ "name": "Karnet", "kind": "UNKNOWN", "audience": "ADULT", "serviceTypeIds": [], "variants": [] }
				""")
			.andExpect(status().isBadRequest());
	}

	@Test
	void everyoneCanListServiceTypes() throws Exception {
		perform(CLIENT_TOKEN, get("/api/v1/service-types?active=true"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].name").value("Calisthenics"));
		perform(CLIENT_TOKEN, get("/api/v1/service-types"), null).andExpect(jsonPath("$.items", hasSize(3)));
	}

	private String createProduct(String name) throws Exception {
		String body = perform(ADMIN_TOKEN, post("/api/v1/pass-products"), productJson(name, poleDance))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String firstVariantId(String productId) {
		return jdbc.sql("SELECT id::text FROM pass_product_variant WHERE product_id = :id LIMIT 1")
			.param("id", UUID.fromString(productId))
			.query(String.class)
			.single();
	}

	private String variantIdWithEntries(String productId, int entries) {
		return jdbc.sql("SELECT id::text FROM pass_product_variant WHERE product_id = :id AND entries_count = :entries")
			.param("id", UUID.fromString(productId))
			.param("entries", entries)
			.query(String.class)
			.single();
	}

	private static String productJson(String name, UUID serviceTypeId) {
		return """
				{ "name": "%s", "description": "Opis", "kind": "ENTRY_PASS", "audience": "ADULT",
				  "suspensionAllowed": true, "maxSuspensionDays": 14, "serviceTypeIds": ["%s"],
				  "variants": [
				    { "entriesCount": 8, "validityDays": 30, "price": { "amountMinor": 26000, "currency": "PLN" } },
				    { "entriesCount": null, "validityDays": 30, "price": { "amountMinor": 29900, "currency": "PLN" } } ] }
				""".formatted(name, serviceTypeId);
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private void addAccount(String email, String firebaseUid, String roleCode, String token) {
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
	}

	private UUID insertServiceType(String name, boolean active) {
		return jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, is_active)
				VALUES (:name, 'GROUP_CLASS', 60, :active)
				RETURNING id
				""")
			.param("name", name)
			.param("active", active)
			.query(UUID.class)
			.single();
	}

}
