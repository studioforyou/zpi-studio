package studioforyou.pl.backend.scheduling.internal;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studioforyou.pl.backend.TestcontainersConfiguration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Firebase ID tokens are mocked at the {@link JwtDecoder}, as in {@code AuthenticationTests};
 * the accounts behind them and their roles are real rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResourceControllerTest {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	private static final String TEST_EMAIL_DOMAIN = "@rooms.test";

	private static final UUID PERSONAL_TRAINING = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID GROUP_CLASS = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final UUID ARCHIVED_SERVICE = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private Filter springSecurityFilterChain;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	/** Sends every request as the studio administrator unless a request sets its own token. */
	private MockMvc mockMvc;

	/** Sends requests without a token. */
	private MockMvc anonymous;

	@BeforeEach
	void resetData() {
		this.jdbcTemplate.update("DELETE FROM service_type_resource");
		this.jdbcTemplate.update("DELETE FROM resource");
		this.jdbcTemplate.update("DELETE FROM service_type");
		String testAccounts = "SELECT id FROM account WHERE email LIKE '%" + TEST_EMAIL_DOMAIN + "'";
		this.jdbcTemplate.update("DELETE FROM account_role WHERE account_id IN (" + testAccounts + ")");
		this.jdbcTemplate.update("DELETE FROM identity_link WHERE account_id IN (" + testAccounts + ")");
		this.jdbcTemplate.update("DELETE FROM account WHERE email LIKE '%" + TEST_EMAIL_DOMAIN + "'");
		insertAccount("admin", "ADMIN", ADMIN_TOKEN);
		insertAccount("trainer", "TRAINER", TRAINER_TOKEN);
		this.anonymous = MockMvcBuilders.webAppContextSetup(this.context)
			.addFilters(this.springSecurityFilterChain)
			.build();
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
			.addFilters(this.springSecurityFilterChain)
			.defaultRequest(MockMvcRequestBuilders.get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.build();
		// The offering module has no API for creating service types yet, so tests insert them directly.
		insertServiceType(PERSONAL_TRAINING, "Personal training", true);
		insertServiceType(GROUP_CLASS, "Calisthenics group", true);
		insertServiceType(ARCHIVED_SERVICE, "Archived service", false);
	}

	@Test
	void createsAndReadsARoom() throws Exception {
		ResultActions created = post("/api/v1/resources", """
				{"name": " Workout room ", "kind": "ROOM", "parentResourceId": null,
				 "maxPeople": 12, "description": "  "}
				""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Workout room"))
			.andExpect(jsonPath("$.kind").value("ROOM"))
			.andExpect(jsonPath("$.capacityUnits").doesNotExist())
			.andExpect(jsonPath("$.maxPeople").value(12))
			.andExpect(jsonPath("$.description").value(nullValue()))
			.andExpect(jsonPath("$.parentResourceId").value(nullValue()))
			.andExpect(jsonPath("$.isActive").value(true));
		String id = id(created);
		created.andExpect(header().string("Location", endsWith("/api/v1/resources/" + id)));

		this.mockMvc.perform(get("/api/v1/resources/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Workout room"));
		this.mockMvc.perform(get("/api/v1/resources"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(id));
	}

	@Test
	void rejectsInvalidRoom() throws Exception {
		post("/api/v1/resources", """
				{"name": "", "kind": "ROOM", "maxPeople": 0}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(2));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "HALL"}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void roomNamesAreUniqueIgnoringCase() throws Exception {
		createRoom("Pole room");
		String other = createRoom("Yoga room");

		post("/api/v1/resources", """
				{"name": "POLE ROOM", "kind": "ROOM"}
				""")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_NAME_ALREADY_USED"));
		patch("/api/v1/resources/" + other, """
				{"name": "pole room"}
				""")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_NAME_ALREADY_USED"));
	}

	@Test
	void patchChangesOnlyGivenFieldsAndNullClearsOptionalOnes() throws Exception {
		String id = id(post("/api/v1/resources", """
				{"name": "Workout room", "kind": "ROOM", "maxPeople": 12, "description": "Mirrors"}
				""").andExpect(status().isCreated()));

		patch("/api/v1/resources/" + id, """
				{"maxPeople": 14}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.maxPeople").value(14))
			.andExpect(jsonPath("$.description").value("Mirrors"));

		patch("/api/v1/resources/" + id, """
				{"maxPeople": null, "description": null}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Workout room"))
			.andExpect(jsonPath("$.maxPeople").value(nullValue()))
			.andExpect(jsonPath("$.description").value(nullValue()));

		patch("/api/v1/resources/" + id, """
				{"maxPeople": 0}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("maxPeople"))
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
	}

	@Test
	void deactivatesARoomInsteadOfDeletingIt() throws Exception {
		String id = createRoom("Old room");
		createRoom("New room");

		patch("/api/v1/resources/" + id, """
				{"isActive": false}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isActive").value(false));

		this.mockMvc.perform(get("/api/v1/resources").param("isActive", "true"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value("New room"));
		this.mockMvc.perform(get("/api/v1/resources").param("isActive", "false"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Old room"));
	}

	@Test
	void parentMustExistAndMustNotCreateACycle() throws Exception {
		String hall = createRoom("Hall");
		String zone = id(post("/api/v1/resources", """
				{"name": "Hall zone", "kind": "ROOM", "parentResourceId": "%s"}
				""".formatted(hall)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentResourceId").value(hall)));

		post("/api/v1/resources", """
				{"name": "Orphan", "kind": "ROOM", "parentResourceId": "%s"}
				""".formatted(UUID.randomUUID()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESOURCE_PARENT_INVALID"));
		patch("/api/v1/resources/" + hall, """
				{"parentResourceId": "%s"}
				""".formatted(hall))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESOURCE_PARENT_INVALID"));
		patch("/api/v1/resources/" + hall, """
				{"parentResourceId": "%s"}
				""".formatted(zone))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESOURCE_PARENT_INVALID"));

		patch("/api/v1/resources/" + zone, """
				{"parentResourceId": null}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.parentResourceId").value(nullValue()));
	}

	@Test
	void unknownRoomIsNotFound() throws Exception {
		this.mockMvc.perform(get("/api/v1/resources/{id}", UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void createsARoomWithItsServiceTypes() throws Exception {
		String room = id(post("/api/v1/resources", """
				{"name": "Workout room", "kind": "ROOM", "serviceTypes": [
				  {"serviceTypeId": "%s", "isDefault": false},
				  {"serviceTypeId": "%s", "isDefault": true}]}
				""".formatted(PERSONAL_TRAINING, GROUP_CLASS)).andExpect(status().isCreated()));

		this.mockMvc.perform(get("/api/v1/resources/{id}/service-types", room))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].serviceTypeName").value("Calisthenics group"))
			.andExpect(jsonPath("$.items[0].isDefault").value(true))
			.andExpect(jsonPath("$.items[1].serviceTypeName").value("Personal training"))
			.andExpect(jsonPath("$.items[1].isDefault").value(false))
			.andExpect(jsonPath("$.items[0].unitsRequired").doesNotExist());
	}

	@Test
	void patchReplacesTheSetOfServiceTypes() throws Exception {
		String room = id(post("/api/v1/resources", """
				{"name": "Workout room", "kind": "ROOM", "serviceTypes": [
				  {"serviceTypeId": "%s"}]}
				""".formatted(PERSONAL_TRAINING)).andExpect(status().isCreated()));

		// Without serviceTypes the set stays as it is.
		patch("/api/v1/resources/" + room, """
				{"maxPeople": 4}
				""").andExpect(status().isOk());
		this.mockMvc.perform(get("/api/v1/resources/{id}/service-types", room))
			.andExpect(jsonPath("$.items.length()").value(1));

		patch("/api/v1/resources/" + room, """
				{"serviceTypes": [{"serviceTypeId": "%s", "isDefault": true}]}
				""".formatted(GROUP_CLASS)).andExpect(status().isOk());
		this.mockMvc.perform(get("/api/v1/resources/{id}/service-types", room))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].serviceTypeId").value(GROUP_CLASS.toString()))
			.andExpect(jsonPath("$.items[0].isDefault").value(true));

		patch("/api/v1/resources/" + room, """
				{"serviceTypes": []}
				""").andExpect(status().isOk());
		this.mockMvc.perform(get("/api/v1/resources/{id}/service-types", room))
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void rejectsUnavailableOrRepeatedServiceTypes() throws Exception {
		post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "serviceTypes": [{"serviceTypeId": "%s"}]}
				""".formatted(ARCHIVED_SERVICE))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_TYPE_UNAVAILABLE"));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "serviceTypes": [{"serviceTypeId": "%s"}]}
				""".formatted(UUID.randomUUID()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_TYPE_UNAVAILABLE"));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "serviceTypes": [
				  {"serviceTypeId": "%1$s"}, {"serviceTypeId": "%1$s"}]}
				""".formatted(PERSONAL_TRAINING))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "serviceTypes": [{"isDefault": true}]}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		// Nothing was saved by the rejected requests.
		this.mockMvc.perform(get("/api/v1/resources")).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void keepsAServiceTypeThatWasDeactivatedAfterItWasAllowed() throws Exception {
		String room = id(post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "serviceTypes": [{"serviceTypeId": "%s"}]}
				""".formatted(PERSONAL_TRAINING)).andExpect(status().isCreated()));
		this.jdbcTemplate.update("UPDATE service_type SET is_active = false WHERE id = ?", PERSONAL_TRAINING);

		patch("/api/v1/resources/" + room, """
				{"serviceTypes": [{"serviceTypeId": "%s", "isDefault": true}]}
				""".formatted(PERSONAL_TRAINING)).andExpect(status().isOk());
		this.mockMvc.perform(get("/api/v1/resources/{id}/service-types", room))
			.andExpect(jsonPath("$.items[0].isDefault").value(true));
	}

	@Test
	void serviceTypeHasAtMostOneDefaultRoom() throws Exception {
		String first = id(post("/api/v1/resources", """
				{"name": "First room", "kind": "ROOM",
				 "serviceTypes": [{"serviceTypeId": "%s", "isDefault": true}]}
				""".formatted(GROUP_CLASS)).andExpect(status().isCreated()));
		post("/api/v1/resources", """
				{"name": "Second room", "kind": "ROOM",
				 "serviceTypes": [{"serviceTypeId": "%s", "isDefault": true}]}
				""".formatted(GROUP_CLASS)).andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/resources/{id}/service-types", first))
			.andExpect(jsonPath("$.items[0].isDefault").value(false));
	}

	@Test
	void roomEndpointsNeedAToken() throws Exception {
		this.anonymous.perform(MockMvcRequestBuilders.get("/api/v1/resources"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void trainersCanOnlyListRooms() throws Exception {
		String room = createRoom("Workout room");

		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.get("/api/v1/resources")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(room));
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.get("/api/v1/resources/{id}", room)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.get("/api/v1/resources/{id}/service-types", room)))
			.andExpect(status().isForbidden());
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.post("/api/v1/resources")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"name": "Trainer's room", "kind": "ROOM"}
					""")))
			.andExpect(status().isForbidden());
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.patch("/api/v1/resources/{id}", room)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"isActive": false}
					""")))
			.andExpect(status().isForbidden());

		this.mockMvc.perform(get("/api/v1/resources"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].isActive").value(true));
	}

	private String createRoom(String name) throws Exception {
		return id(post("/api/v1/resources", """
				{"name": "%s", "kind": "ROOM"}
				""".formatted(name)).andExpect(status().isCreated()));
	}

	private ResultActions post(String path, String json) throws Exception {
		return this.mockMvc
			.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions patch(String path, String json) throws Exception {
		return this.mockMvc
			.perform(MockMvcRequestBuilders.patch(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String id(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private static MockHttpServletRequestBuilder asTrainer(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TRAINER_TOKEN);
	}

	/** An active account with one role, signed in with the given (mocked) Firebase token. */
	private void insertAccount(String name, String roleCode, String token) {
		UUID id = this.jdbcTemplate.queryForObject(
				"INSERT INTO account (email, status) VALUES (?, 'ACTIVE') RETURNING id", UUID.class,
				name + TEST_EMAIL_DOMAIN);
		String firebaseUid = "uid-" + name;
		this.jdbcTemplate.update(
				"INSERT INTO identity_link (account_id, provider, external_uid) VALUES (?, 'FIREBASE', ?)", id,
				firebaseUid);
		this.jdbcTemplate.update("INSERT INTO account_role (account_id, role_id) SELECT ?, id FROM role WHERE code = ?",
				id, roleCode);
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(this.jwtDecoder.decode(token)).willReturn(jwt);
	}

	private void insertServiceType(UUID id, String name, boolean active) {
		this.jdbcTemplate.update("""
				INSERT INTO service_type (id, name, category, default_duration_minutes, is_active)
				VALUES (?, ?, 'GROUP', 60, ?)
				""", id, name, active);
	}

}
