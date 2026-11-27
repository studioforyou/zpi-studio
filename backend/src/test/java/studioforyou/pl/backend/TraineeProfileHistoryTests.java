package studioforyou.pl.backend;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * A trainer sees the change history of the profile of a participant they work with (#56):
 * personal details changed by the participant, a guardian or an administrator, card changes made
 * by trainers, and health notes added or archived, the last only with access to health data.
 * <p>
 * The tests are not transactional, so requests run as in production; the data is removed after
 * each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TraineeProfileHistoryTests {

	private static final String TRAINER_TOKEN = "history-trainer-token";

	private static final String OTHER_TRAINER_TOKEN = "history-other-trainer-token";

	private static final String ADMIN_TOKEN = "history-admin-token";

	private static final String CLIENT_TOKEN = "history-client-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID trainerAccount;

	private UUID trainer;

	private UUID otherTrainer;

	private UUID clientAccount;

	private UUID client;

	private UUID child;

	@BeforeEach
	void setUp() {
		trainerAccount = addAccount("history-trainer@example.com", "uid-history-trainer", "TRAINER", TRAINER_TOKEN);
		trainer = insertStaff(trainerAccount, "Marek", "Nowak");
		UUID otherTrainerAccount = addAccount("history-trainer2@example.com", "uid-history-trainer2", "TRAINER",
				OTHER_TRAINER_TOKEN);
		otherTrainer = insertStaff(otherTrainerAccount, "Ewa", "Wiśniewska");
		UUID adminAccount = addAccount("history-admin@example.com", "uid-history-admin", "ADMIN", ADMIN_TOKEN);
		insertStaff(adminAccount, "Beata", "Lis");
		clientAccount = addAccount("history-client@example.com", "uid-history-client", "CLIENT", CLIENT_TOKEN);
		client = insertCustomer(clientAccount, "Katarzyna", "Zielińska", "ADULT");
		child = insertCustomer(null, "Zosia", "Zielińska", "CHILD");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", client)
			.param("child", child)
			.update();
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE 'history-%@example.com'";
		List<UUID> customers = List.of(client, child);
		jdbc.sql("DELETE FROM audit_log WHERE actor_account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM health_note WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM trainee_card WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM staff_assignment WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM guardianship WHERE child_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM account_permission_override WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE 'history-%@example.com'").update();
	}

	@Test
	void aTrainerSeesChangesOfPersonalDetailsAndTheCardNewestFirst() throws Exception {
		assign(client, trainer, false);
		perform(CLIENT_TOKEN, patch("/api/v1/me/customer-profiles/" + client), """
				{ "phone": "600 300 400" }
				""").andExpect(status().isOk());
		perform(TRAINER_TOKEN, patch(card(client)), "{ \"goals\": \"Szpagat.\", \"version\": 0 }")
			.andExpect(status().isOk());
		perform(CLIENT_TOKEN, patch("/api/v1/me/customer-profiles/" + client), """
				{ "emergencyContactName": "Michał Zieliński", "emergencyPhone": "601 222 333" }
				""").andExpect(status().isOk());

		perform(TRAINER_TOKEN, get(history(client)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.healthAccess").value("NO_PERMISSION"))
			.andExpect(jsonPath("$.totalItems").value(3))
			.andExpect(jsonPath("$.items", hasSize(3)))
			.andExpect(jsonPath("$.items[0].group").value("PERSONAL"))
			.andExpect(jsonPath("$.items[0].action").value("PROFILE_UPDATED"))
			.andExpect(jsonPath("$.items[0].actor.name").value("Katarzyna Zielińska"))
			.andExpect(jsonPath("$.items[0].actor.role").value("CLIENT"))
			.andExpect(jsonPath("$.items[0].changes", hasSize(2)))
			.andExpect(jsonPath("$.items[0].changes[0].field").value("emergencyContactName"))
			.andExpect(jsonPath("$.items[0].changes[0].oldValue").value(nullValue()))
			.andExpect(jsonPath("$.items[0].changes[0].newValue").value("Michał Zieliński"))
			.andExpect(jsonPath("$.items[1].group").value("CARD"))
			.andExpect(jsonPath("$.items[1].action").value("CARD_UPDATED"))
			.andExpect(jsonPath("$.items[1].actor.name").value("Marek Nowak"))
			.andExpect(jsonPath("$.items[1].actor.role").value("TRAINER"))
			.andExpect(jsonPath("$.items[1].changes[0].field").value("goals"))
			.andExpect(jsonPath("$.items[1].changes[0].newValue").value("Szpagat."))
			.andExpect(jsonPath("$.items[2].changes[0].field").value("phone"))
			.andExpect(jsonPath("$.items[2].changes[0].newValue").value("600 300 400"));

		perform(TRAINER_TOKEN, get(history(client)).param("group", "CARD"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].group").value("CARD"));
		perform(TRAINER_TOKEN, get(history(client)).param("size", "2").param("page", "1"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].changes[0].field").value("phone"));
	}

	@Test
	void anEditThatChangesNothingIsNotRecorded() throws Exception {
		assign(client, trainer, false);
		perform(CLIENT_TOKEN, patch("/api/v1/me/customer-profiles/" + client), """
				{ "firstName": " Katarzyna " }
				""").andExpect(status().isOk());
		perform(TRAINER_TOKEN, patch(card(client)), "{ \"goals\": \"Szpagat.\", \"version\": 0 }")
			.andExpect(status().isOk());
		perform(TRAINER_TOKEN, patch(card(client)), "{ \"goals\": \"Szpagat.\", \"version\": 1 }")
			.andExpect(status().isOk());

		perform(TRAINER_TOKEN, get(history(client)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].group").value("CARD"));
	}

	@Test
	void changesOfAGuardianAndOfAnAdministratorAreShownWithTheirRole() throws Exception {
		assign(child, trainer, false);
		perform(CLIENT_TOKEN, patch("/api/v1/me/customer-profiles/" + child), """
				{ "lastName": "Zielińska-Kot", "birthDate": "2016-05-08" }
				""").andExpect(status().isOk());
		perform(ADMIN_TOKEN, put("/api/v1/accounts/" + clientAccount + "/children/" + child), """
				{ "firstName": "Zofia", "lastName": "Zielińska-Kot", "birthDate": "2016-05-08",
				  "emergencyContactName": null, "emergencyPhone": null }
				""").andExpect(status().isOk());

		perform(TRAINER_TOKEN, get(history(child)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].actor.name").value("Beata Lis"))
			.andExpect(jsonPath("$.items[0].actor.role").value("ADMIN"))
			.andExpect(jsonPath("$.items[0].changes", hasSize(1)))
			.andExpect(jsonPath("$.items[0].changes[0].field").value("firstName"))
			.andExpect(jsonPath("$.items[0].changes[0].oldValue").value("Zosia"))
			.andExpect(jsonPath("$.items[0].changes[0].newValue").value("Zofia"))
			.andExpect(jsonPath("$.items[1].actor.role").value("GUARDIAN"))
			.andExpect(jsonPath("$.items[1].changes[0].field").value("lastName"))
			.andExpect(jsonPath("$.items[1].changes[1].field").value("birthDate"))
			.andExpect(jsonPath("$.items[1].changes[1].newValue").value("2016-05-08"));
	}

	@Test
	void anAdministratorsChangeOfAnAccountIsRecordedInItsProfile() throws Exception {
		assign(client, trainer, false);
		perform(ADMIN_TOKEN, put("/api/v1/accounts/" + clientAccount), """
				{ "email": "history-client@example.com", "firstName": "Kasia", "lastName": "Zielińska",
				  "phone": "500 100 200" }
				""").andExpect(status().isOk());

		perform(TRAINER_TOKEN, get(history(client)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].actor.role").value("ADMIN"))
			.andExpect(jsonPath("$.items[0].changes[0].field").value("firstName"))
			.andExpect(jsonPath("$.items[0].changes[1].field").value("phone"));
	}

	@Test
	void healthEntriesNeedAccessAndNeverShowTheNotesText() throws Exception {
		assign(client, trainer, true);
		assign(client, otherTrainer, false);
		grantHealthPermission(trainerAccount);
		String location = perform(TRAINER_TOKEN, post(healthNotes(client)), """
				{ "kind": "INJURY", "description": "Uraz lewego kolana." }
				""").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String noteId = JsonPath.read(location, "$.id");
		perform(TRAINER_TOKEN, post(healthNotes(client) + "/" + noteId + "/archive"), null)
			.andExpect(status().isOk());
		// Archiving again changes nothing.
		perform(TRAINER_TOKEN, post(healthNotes(client) + "/" + noteId + "/archive"), null)
			.andExpect(status().isOk());

		perform(TRAINER_TOKEN, get(history(client)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.healthAccess").value("GRANTED"))
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].group").value("HEALTH"))
			.andExpect(jsonPath("$.items[0].action").value("HEALTH_NOTE_ARCHIVED"))
			.andExpect(jsonPath("$.items[0].healthNoteId").value(noteId))
			.andExpect(jsonPath("$.items[0].healthNoteKind").value("INJURY"))
			.andExpect(jsonPath("$.items[0].changes", hasSize(0)))
			.andExpect(jsonPath("$.items[1].action").value("HEALTH_NOTE_ADDED"));
		perform(TRAINER_TOKEN, get(history(client)).param("group", "HEALTH"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)));

		// The other trainer has no consent: no health entries at all.
		perform(OTHER_TRAINER_TOKEN, get(history(client)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.healthAccess").value("NO_PERMISSION"))
			.andExpect(jsonPath("$.items", hasSize(0)));
		perform(OTHER_TRAINER_TOKEN, get(history(client)).param("group", "HEALTH"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	void theHistoryIsOnlyForTheParticipantsCurrentTrainers() throws Exception {
		assign(client, trainer, false);

		perform(OTHER_TRAINER_TOKEN, get(history(client)), null).andExpect(status().isNotFound());
		perform(CLIENT_TOKEN, get(history(client)), null).andExpect(status().isForbidden());
		perform(TRAINER_TOKEN, get(history(client)).param("size", "101"), null).andExpect(status().isBadRequest());

		jdbc.sql("UPDATE staff_assignment SET ended_at = now() WHERE customer_id = :id").param("id", client).update();
		perform(TRAINER_TOKEN, get(history(client)), null).andExpect(status().isNotFound());
	}

	private static String history(UUID customerId) {
		return "/api/v1/me/trainees/" + customerId + "/profile-history";
	}

	private static String card(UUID customerId) {
		return "/api/v1/me/trainees/" + customerId + "/card";
	}

	private static String healthNotes(UUID customerId) {
		return "/api/v1/me/trainees/" + customerId + "/health-notes";
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private void assign(UUID customerId, UUID staffId, boolean healthShared) {
		jdbc.sql("""
				INSERT INTO staff_assignment (customer_id, staff_id, scope, can_view_health, assigned_by)
				VALUES (:customer, :staff, 'PERSONAL_TRAINING', :shared, :assignedBy)
				""")
			.param("customer", customerId)
			.param("staff", staffId)
			.param("shared", healthShared)
			.param("assignedBy", trainerAccount)
			.update();
	}

	private void grantHealthPermission(UUID accountId) {
		jdbc.sql("""
				INSERT INTO account_permission_override (account_id, permission_id, effect, granted_by)
				SELECT :id, id, 'GRANT', :id FROM permission WHERE code = 'CUSTOMER_HEALTH_READ'
				""").param("id", accountId).update();
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

	private UUID insertStaff(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name)
				VALUES (:accountId, :firstName, :lastName)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.query(UUID.class)
			.single();
	}

	private UUID insertCustomer(UUID accountId, String firstName, String lastName, String kind) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, first_name, last_name, kind)
				VALUES (:accountId, :firstName, :lastName, :kind)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("kind", kind)
			.query(UUID.class)
			.single();
	}

}
