package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import studioforyou.pl.backend.offering.ServiceTypes;

/** Requests exercise authorization and committed PostgreSQL writes with mocked Firebase tokens. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ServiceTypeTests {

	private static final String PATH = "/api/v1/service-types";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ServiceTypes serviceTypes;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void setUp() {
		addAccount("admin", "ADMIN", "ACTIVE");
		addAccount("trainer", "TRAINER", "ACTIVE");
		addAccount("client", "CLIENT", "ACTIVE");
		addAccount("suspended", "ADMIN", "SUSPENDED");
	}

	@AfterEach
	void tearDown() {
		String services = "SELECT id FROM service_type WHERE name LIKE 'service-test-%'";
		jdbc.sql("DELETE FROM service_type_resource WHERE service_type_id IN (" + services + ")").update();
		jdbc.sql("DELETE FROM pass_product_service WHERE service_type_id IN (" + services + ")").update();
		jdbc.sql("DELETE FROM resource WHERE name LIKE 'service-test-%'").update();
		jdbc.sql("DELETE FROM booking_policy WHERE service_type_id IN (" + services + ") OR created_by IN "
				+ "(SELECT id FROM account WHERE email LIKE '%@services.test')").update();
		jdbc.sql("DELETE FROM service_type WHERE name LIKE 'service-test-%'").update();
		String accounts = "SELECT id FROM account WHERE email LIKE '%@services.test'";
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%@services.test'").update();
	}

	@Test
	void adminCreatesAndReadsTheCompleteDefinition() throws Exception {
		String response = perform("admin", post(PATH), input())
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.name").value("service-test-Pole dance"))
			.andExpect(jsonPath("$.description").value("Training description"))
			.andExpect(jsonPath("$.category").value("GROUP_CLASS"))
			.andExpect(jsonPath("$.level").value("BEGINNER"))
			.andExpect(jsonPath("$.minAge").value(16))
			.andExpect(jsonPath("$.maxAge").value(70))
			.andExpect(jsonPath("$.defaultDurationMinutes").value(60))
			.andExpect(jsonPath("$.minParticipants").value(3))
			.andExpect(jsonPath("$.maxParticipants").value(12))
			.andExpect(jsonPath("$.calendarColor").value("#2196F3"))
			.andExpect(jsonPath("$.isActive").value(true))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(response, "$.id");
		perform("admin", get(PATH + "/" + id), null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.minAge").value(16));
		assertThat(jdbc.sql("SELECT name FROM service_type WHERE id = :id")
			.param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo("service-test-Pole dance");
	}

	@Test
	void partialUpdateKeepsMissingValuesAndCanClearOptionalFields() throws Exception {
		String id = create();
		perform("admin", patch(PATH + "/" + id), """
				{"description":null,"level":null,"minAge":0,"maxAge":null,
				 "minParticipants":null,"maxParticipants":null,"calendarColor":null}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("service-test-Pole dance"))
			.andExpect(jsonPath("$.defaultDurationMinutes").value(60))
			.andExpect(jsonPath("$.description").isEmpty())
			.andExpect(jsonPath("$.level").isEmpty())
			.andExpect(jsonPath("$.minAge").value(0))
			.andExpect(jsonPath("$.maxAge").isEmpty())
			.andExpect(jsonPath("$.maxParticipants").isEmpty())
			.andExpect(jsonPath("$.calendarColor").isEmpty());
		perform("admin", patch(PATH + "/" + id), "{}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.minAge").value(0));
	}

	@Test
	void rangeValidationUsesTheMergedValuesAndRollsBackOtherChanges() throws Exception {
		String id = create();
		perform("admin", patch(PATH + "/" + id), "{\"name\":\"service-test-Changed\",\"maxAge\":10}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("maxAge"));
		perform("admin", patch(PATH + "/" + id), "{\"minParticipants\":13}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("maxParticipants"));
		perform("admin", get(PATH + "/" + id), null)
			.andExpect(jsonPath("$.name").value("service-test-Pole dance"))
			.andExpect(jsonPath("$.minParticipants").value(3));
	}

	@Test
	void invalidValuesAreRejectedBeforeSaving() throws Exception {
		String id = create();
		for (String body : new String[] {
				"{\"name\":\" \"}", "{\"category\":\"UNKNOWN\"}",
				"{\"defaultDurationMinutes\":0}", "{\"defaultDurationMinutes\":32768}",
				"{\"minAge\":-1}", "{\"maxAge\":32768}", "{\"minParticipants\":0}",
				"{\"calendarColor\":\"red\"}", "{\"level\":\"" + "x".repeat(21) + "\"}",
				"{\"name\":\"" + "x".repeat(121) + "\"}" }) {
			perform("admin", patch(PATH + "/" + id), body)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		perform("admin", post(PATH), "{}").andExpect(status().isBadRequest());
	}

	@Test
	void onlyAnActiveAdminCanWriteButOtherActiveAccountsCanRead() throws Exception {
		String id = create();
		for (String token : new String[] { "client", "trainer", "suspended" }) {
			perform(token, post(PATH), input()).andExpect(status().isForbidden());
			perform(token, patch(PATH + "/" + id), "{\"isActive\":false}").andExpect(status().isForbidden());
		}
		perform(null, post(PATH), input()).andExpect(status().isUnauthorized());
		perform(null, patch(PATH + "/" + id), "{}").andExpect(status().isUnauthorized());
		perform("client", get(PATH), null).andExpect(status().isOk());
		perform("trainer", get(PATH + "/" + id), null).andExpect(status().isOk());
		perform("suspended", get(PATH), null).andExpect(status().isForbidden());
	}

	@Test
	void deactivationPreservesRoomReferencesAndTheServiceCanBeRestored() throws Exception {
		String id = create();
		UUID room = jdbc.sql("INSERT INTO resource (name, kind) VALUES ('service-test-room','ROOM') RETURNING id")
			.query(UUID.class).single();
		jdbc.sql("INSERT INTO service_type_resource (service_type_id,resource_id,is_default) VALUES (:service,:room,true)")
			.param("service", UUID.fromString(id)).param("room", room).update();
		perform("admin", patch(PATH + "/" + id), "{\"isActive\":false}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.isActive").value(false));
		perform("admin", get(PATH + "?active=false"), null)
			.andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").isNotEmpty());
		perform("admin", get(PATH + "?active=true"), null)
			.andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").isEmpty());
		perform("admin", get("/api/v1/resources/" + room + "/service-types"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].serviceTypeId").value(id))
			.andExpect(jsonPath("$.items[0].serviceTypeName").value("service-test-Pole dance"));
		perform("admin", patch(PATH + "/" + id), "{\"isActive\":true,\"name\":\"service-test-Renamed\"}")
			.andExpect(status().isOk());
		perform("admin", get("/api/v1/resources/" + room + "/service-types"), null)
			.andExpect(jsonPath("$.items[0].serviceTypeName").value("service-test-Renamed"));
	}

	@Test
	void unknownIdentifiersAndMalformedBodiesUseApiErrors() throws Exception {
		String path = PATH + "/" + UUID.randomUUID();
		perform("admin", get(path), null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform("admin", patch(path), "{}").andExpect(status().isNotFound());
		perform("admin", post(PATH), "{").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void limitsOnCreationMustBeConsistent() throws Exception {
		perform("admin", post(PATH), input().replace("\"maxAge\":70", "\"maxAge\":10"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("maxAge"));
		perform("admin", post(PATH), input().replace("\"maxParticipants\":12", "\"maxParticipants\":2"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("maxParticipants"));
	}

	@Test
	void bookingRulesAreVersionedAndRepeatedSavesKeepTheCurrentVersion() throws Exception {
		String id = create();
		String rules = """
				{"bookingPolicy":{"cancellationAllowed":true,"freeCancelHours":6,"lateCancelAllowed":false,
				"lateCancelForfeitsEntry":true,"bookingOpensDaysBefore":7,"bookingClosesMinutesBefore":60,
				"waitlistEnabled":true,"waitlistConfirmMinutes":15,"autoCancelEnabled":true,
				"autoCancelMinParticipants":3,"autoCancelHoursBefore":4}}
				""";
		String response = perform("admin", patch(PATH + "/" + id), rules).andExpect(status().isOk())
			.andExpect(jsonPath("$.bookingPolicy.effective.freeCancelHours").value(6))
			.andExpect(jsonPath("$.bookingPolicy.effective.lateCancelAllowed").value(false))
			.andExpect(jsonPath("$.bookingPolicy.effective.autoCancelHoursBefore").value(4))
			.andExpect(jsonPath("$.bookingPolicy.effective.waitlistConfirmMinutes").value(15))
			.andReturn().getResponse().getContentAsString();
		String firstPolicy = JsonPath.read(response, "$.bookingPolicy.policyId");
		var definition = this.serviceTypes.findDefinitionById(UUID.fromString(id)).orElseThrow();
		assertThat(definition.bookingPolicy().effective().freeCancelHours()).isEqualTo(6);
		assertThat(definition.bookingPolicy().effective().autoCancelEnabled()).isTrue();
		perform("admin", patch(PATH + "/" + id), rules).andExpect(status().isOk())
			.andExpect(jsonPath("$.bookingPolicy.policyId").value(firstPolicy));
		perform("admin", patch(PATH + "/" + id), rules.replace("\"freeCancelHours\":6", "\"freeCancelHours\":12"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.bookingPolicy.effective.freeCancelHours").value(12));
		assertThat(jdbc.sql("SELECT count(*) FROM booking_policy WHERE service_type_id = :id")
			.param("id", UUID.fromString(id)).query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT valid_to IS NOT NULL FROM booking_policy WHERE id = :id")
			.param("id", UUID.fromString(firstPolicy)).query(Boolean.class).single()).isTrue();
	}

	@Test
	void resettingRulesRestoresStudioDefaultsAndPersonalTrainingDoesNotInheritGroupAutomation() throws Exception {
		UUID actor = jdbc.sql("SELECT id FROM account WHERE email = 'admin@services.test'").query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO booking_policy (scope, free_cancel_hours, late_cancel_allowed, auto_cancel_enabled,
				auto_cancel_min_participants, auto_cancel_hours_before, created_by)
				VALUES ('STUDIO', 6, false, true, 3, 4, :actor)
				""").param("actor", actor).update();
		String id = create();
		perform("admin", get(PATH + "/" + id), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.bookingPolicy.overrides").isEmpty())
			.andExpect(jsonPath("$.bookingPolicy.effective.freeCancelHours").value(6));
		perform("admin", patch(PATH + "/" + id), "{\"bookingPolicy\":{\"freeCancelHours\":2}}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.bookingPolicy.effective.freeCancelHours").value(2));
		perform("admin", patch(PATH + "/" + id), "{\"bookingPolicy\":null}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.bookingPolicy.overrides").isEmpty())
			.andExpect(jsonPath("$.bookingPolicy.effective.freeCancelHours").value(6));
		perform("admin", post(PATH), personalInput()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.minParticipants").value(1))
			.andExpect(jsonPath("$.maxParticipants").value(1))
			.andExpect(jsonPath("$.bookingPolicy.effective.autoCancelEnabled").value(false))
			.andExpect(jsonPath("$.bookingPolicy.effective.waitlistEnabled").value(false))
			.andExpect(jsonPath("$.bookingPolicy.effective.freeCancelHours").value(6));
	}

	@Test
	void invalidBookingRulesRollBackTheServiceChange() throws Exception {
		String id = create();
		perform("admin", patch(PATH + "/" + id), """
				{"name":"service-test-Changed","bookingPolicy":{"autoCancelEnabled":true,"autoCancelMinParticipants":13}}
				""").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("bookingPolicy.autoCancelMinParticipants"));
		perform("admin", get(PATH + "/" + id), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("service-test-Pole dance"))
			.andExpect(jsonPath("$.bookingPolicy.overrides").isEmpty());
		perform("admin", patch(PATH + "/" + id), "{\"bookingPolicy\":{\"freeCancelHours\":-1}}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void personalTrainingSupportsSmallGroupsButRejectsGroupOnlyRules() throws Exception {
		String invalidPolicy = personalInput().replace("\"calendarColor\":\"#2196F3\"",
				"\"calendarColor\":\"#2196F3\",\"bookingPolicy\":{\"autoCancelEnabled\":true}");
		perform("admin", post(PATH), invalidPolicy).andExpect(status().isBadRequest());
		String response = perform("admin", post(PATH), personalInput()
			.replace("\"minParticipants\":1", "\"minParticipants\":2")
			.replace("\"maxParticipants\":1", "\"maxParticipants\":3"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.category").value("PERSONAL_TRAINING"))
			.andExpect(jsonPath("$.minParticipants").value(2))
			.andExpect(jsonPath("$.maxParticipants").value(3))
			.andExpect(jsonPath("$.bookingPolicy.effective.autoCancelEnabled").value(false))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(response, "$.id");
		perform("admin", patch(PATH + "/" + id), "{\"minParticipants\":1,\"maxParticipants\":2}")
			.andExpect(status().isOk()).andExpect(jsonPath("$.maxParticipants").value(2));
		perform("admin", patch(PATH + "/" + id), "{\"minParticipants\":3}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("maxParticipants"));
		perform("admin", get(PATH + "/" + id), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.minParticipants").value(1));
	}

	private static String personalInput() {
		return input().replace("GROUP_CLASS", "PERSONAL_TRAINING")
			.replace("\"minParticipants\":3", "\"minParticipants\":1")
			.replace("\"maxParticipants\":12", "\"maxParticipants\":1");
	}

	private String create() throws Exception {
		String json = perform("admin", post(PATH), input()).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.id");
	}

	private static String input() {
		return """
				{"name":" service-test-Pole dance ","description":" Training description ","category":"GROUP_CLASS",
				 "level":" BEGINNER ","minAge":16,"maxAge":70,"defaultDurationMinutes":60,
				 "minParticipants":3,"maxParticipants":12,"calendarColor":"#2196F3"}
				""";
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private void addAccount(String name, String role, String status) {
		UUID id = jdbc.sql("INSERT INTO account (email,status) VALUES (:email,:status) RETURNING id")
			.param("email", name + "@services.test").param("status", status).query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id,provider,external_uid) VALUES (:id,'FIREBASE',:uid)")
			.param("id", id).param("uid", "service-test-" + name).update();
		jdbc.sql("INSERT INTO account_role (account_id,role_id) SELECT :id,id FROM role WHERE code=:role")
			.param("id", id).param("role", role).update();
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256")
			.subject("service-test-" + name).build());
	}

}
