package studioforyou.pl.backend.scheduling.internal;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studioforyou.pl.backend.TestcontainersConfiguration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResourceControllerTest {

	private static final UUID PERSONAL_TRAINING = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID GROUP_CLASS = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final UUID ARCHIVED_SERVICE = UUID.fromString("00000000-0000-0000-0000-000000000003");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void resetData() {
		this.jdbcTemplate.update("DELETE FROM service_type_resource");
		this.jdbcTemplate.update("DELETE FROM resource");
		this.jdbcTemplate.update("DELETE FROM service_type");
		// The offering module has no API for creating service types yet, so tests insert them directly.
		insertServiceType(PERSONAL_TRAINING, "Personal training", true);
		insertServiceType(GROUP_CLASS, "Calisthenics group", true);
		insertServiceType(ARCHIVED_SERVICE, "Archived service", false);
	}

	@Test
	void createsAndReadsARoom() throws Exception {
		ResultActions created = post("/api/v1/resources", """
				{"name": " Workout room ", "kind": "ROOM", "parentResourceId": null, "capacityUnits": 3,
				 "maxPeople": 12, "description": "  "}
				""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Workout room"))
			.andExpect(jsonPath("$.kind").value("ROOM"))
			.andExpect(jsonPath("$.capacityUnits").value(3))
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
				{"name": "", "kind": "ROOM", "capacityUnits": 0, "maxPeople": 0}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(3));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "HALL", "capacityUnits": 1}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void roomNamesAreUniqueIgnoringCase() throws Exception {
		createRoom("Pole room", 1);
		String other = createRoom("Yoga room", 1);

		post("/api/v1/resources", """
				{"name": "POLE ROOM", "kind": "ROOM", "capacityUnits": 1}
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
				{"name": "Workout room", "kind": "ROOM", "capacityUnits": 3, "maxPeople": 12, "description": "Mirrors"}
				""").andExpect(status().isCreated()));

		patch("/api/v1/resources/" + id, """
				{"capacityUnits": 4}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capacityUnits").value(4))
			.andExpect(jsonPath("$.maxPeople").value(12))
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
		String id = createRoom("Old room", 1);
		createRoom("New room", 1);

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
		String hall = createRoom("Hall", 2);
		String zone = id(post("/api/v1/resources", """
				{"name": "Hall zone", "kind": "ROOM", "capacityUnits": 1, "parentResourceId": "%s"}
				""".formatted(hall)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.parentResourceId").value(hall)));

		post("/api/v1/resources", """
				{"name": "Orphan", "kind": "ROOM", "capacityUnits": 1, "parentResourceId": "%s"}
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
				{"name": "Workout room", "kind": "ROOM", "capacityUnits": 3, "serviceTypes": [
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
				{"name": "Workout room", "kind": "ROOM", "capacityUnits": 3, "serviceTypes": [
				  {"serviceTypeId": "%s"}]}
				""".formatted(PERSONAL_TRAINING)).andExpect(status().isCreated()));

		// Without serviceTypes the set stays as it is.
		patch("/api/v1/resources/" + room, """
				{"capacityUnits": 4}
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
				{"name": "Room", "kind": "ROOM", "capacityUnits": 1, "serviceTypes": [{"serviceTypeId": "%s"}]}
				""".formatted(ARCHIVED_SERVICE))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_TYPE_NOT_AVAILABLE"));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "capacityUnits": 1, "serviceTypes": [{"serviceTypeId": "%s"}]}
				""".formatted(UUID.randomUUID()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_TYPE_NOT_AVAILABLE"));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "capacityUnits": 1, "serviceTypes": [
				  {"serviceTypeId": "%1$s"}, {"serviceTypeId": "%1$s"}]}
				""".formatted(PERSONAL_TRAINING))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "capacityUnits": 1, "serviceTypes": [{"isDefault": true}]}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		// Nothing was saved by the rejected requests.
		this.mockMvc.perform(get("/api/v1/resources")).andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void keepsAServiceTypeThatWasDeactivatedAfterItWasAllowed() throws Exception {
		String room = id(post("/api/v1/resources", """
				{"name": "Room", "kind": "ROOM", "capacityUnits": 1, "serviceTypes": [{"serviceTypeId": "%s"}]}
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
				{"name": "First room", "kind": "ROOM", "capacityUnits": 1,
				 "serviceTypes": [{"serviceTypeId": "%s", "isDefault": true}]}
				""".formatted(GROUP_CLASS)).andExpect(status().isCreated()));
		post("/api/v1/resources", """
				{"name": "Second room", "kind": "ROOM", "capacityUnits": 1,
				 "serviceTypes": [{"serviceTypeId": "%s", "isDefault": true}]}
				""".formatted(GROUP_CLASS)).andExpect(status().isCreated());

		this.mockMvc.perform(get("/api/v1/resources/{id}/service-types", first))
			.andExpect(jsonPath("$.items[0].isDefault").value(false));
	}

	@Test
	void listsOnlyActiveServiceTypes() throws Exception {
		this.mockMvc.perform(get("/api/v1/service-types"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].name").value("Calisthenics group"))
			.andExpect(jsonPath("$.items[1].name").value("Personal training"));
	}

	private String createRoom(String name, int capacityUnits) throws Exception {
		return id(post("/api/v1/resources", """
				{"name": "%s", "kind": "ROOM", "capacityUnits": %d}
				""".formatted(name, capacityUnits)).andExpect(status().isCreated()));
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

	private void insertServiceType(UUID id, String name, boolean active) {
		this.jdbcTemplate.update("""
				INSERT INTO service_type (id, name, category, default_duration_minutes, is_active)
				VALUES (?, ?, 'GROUP', 60, ?)
				""", id, name, active);
	}

}
