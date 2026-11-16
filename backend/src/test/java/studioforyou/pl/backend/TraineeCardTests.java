package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * Trainers keep the card of the participants they work with (#99): goals and sport experience,
 * and the health notes a participant shared with them. Firebase ID tokens are mocked at the
 * {@link JwtDecoder}, as in the other trainer tests.
 * <p>
 * The tests are not transactional, so requests run as in production; the data is removed after
 * each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TraineeCardTests {

	private static final String TRAINEES = "/api/v1/me/trainees/";

	private static final String TRAINER_TOKEN = "card-trainer-token";

	private static final String OTHER_TRAINER_TOKEN = "card-other-trainer-token";

	private static final String NO_PROFILE_TRAINER_TOKEN = "card-no-profile-trainer-token";

	private static final String ADMIN_TOKEN = "card-admin-token";

	private static final String CLIENT_TOKEN = "card-client-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID trainerAccount;

	private UUID trainer;

	private UUID otherTrainerAccount;

	private UUID otherTrainer;

	private UUID clientAccount;

	private UUID trainee;

	private UUID otherTrainee;

	@BeforeEach
	void setUp() {
		trainerAccount = addAccount("card-trainer@example.com", "uid-card-trainer", "TRAINER", TRAINER_TOKEN);
		trainer = insertStaff(trainerAccount, "Marek", "Nowak");
		otherTrainerAccount = addAccount("card-trainer2@example.com", "uid-card-trainer2", "TRAINER",
				OTHER_TRAINER_TOKEN);
		otherTrainer = insertStaff(otherTrainerAccount, "Ewa", "Wiśniewska");
		addAccount("card-trainer3@example.com", "uid-card-trainer3", "TRAINER", NO_PROFILE_TRAINER_TOKEN);
		insertStaff(addAccount("card-admin@example.com", "uid-card-admin", "ADMIN", ADMIN_TOKEN), "Beata", "Lis");
		clientAccount = addAccount("card-client@example.com", "uid-card-client", "CLIENT", CLIENT_TOKEN);
		trainee = insertCustomer(clientAccount, "Tomasz", "Lewandowski");
		otherTrainee = insertCustomer(null, "Katarzyna", "Zielińska");
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE 'card-%@example.com'";
		List<UUID> customers = List.of(trainee, otherTrainee);
		jdbc.sql("DELETE FROM health_note WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM trainee_card WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM staff_assignment WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM account_permission_override WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE 'card-%@example.com'").update();
	}

	@Test
	void aTrainerFillsInTheCardOfTheirTrainee() throws Exception {
		assign(trainee, trainer, false);

		perform(TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customerId").value(trainee.toString()))
			.andExpect(jsonPath("$.goals").value(nullValue()))
			.andExpect(jsonPath("$.sportExperience").value(nullValue()))
			.andExpect(jsonPath("$.version").value(0))
			.andExpect(jsonPath("$.updatedAt").value(nullValue()))
			.andExpect(jsonPath("$.updatedBy").value(nullValue()));

		perform(TRAINER_TOKEN, patch(card(trainee)), """
				{ "goals": "  Pierwsze podciągnięcie na drążku.  ", "sportExperience": "Siłownia 2 lata.", "version": 0 }
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.goals").value("Pierwsze podciągnięcie na drążku."))
			.andExpect(jsonPath("$.sportExperience").value("Siłownia 2 lata."))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.updatedAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedBy").value("Marek Nowak"));

		Map<String, Object> stored = jdbc.sql("""
				SELECT goals, sport_experience, updated_by, version FROM trainee_card WHERE customer_id = :id
				""").param("id", trainee).query().singleRow();
		assertThat(stored).containsEntry("goals", "Pierwsze podciągnięcie na drążku.")
			.containsEntry("sport_experience", "Siłownia 2 lata.")
			.containsEntry("updated_by", trainerAccount)
			.containsEntry("version", 1);
	}

	@Test
	void omittedFieldsStayUnchangedAndBlankTextClearsAField() throws Exception {
		assign(trainee, trainer, false);
		perform(TRAINER_TOKEN, patch(card(trainee)), """
				{ "goals": "Szpagat.", "sportExperience": "Taniec współczesny.", "version": 0 }
				""").andExpect(status().isOk());

		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"Szpagat poprzeczny.\", \"version\": 1 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.goals").value("Szpagat poprzeczny."))
			.andExpect(jsonPath("$.sportExperience").value("Taniec współczesny."))
			.andExpect(jsonPath("$.version").value(2));
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"sportExperience\": \" \\u00a0 \", \"version\": 2 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.goals").value("Szpagat poprzeczny."))
			.andExpect(jsonPath("$.sportExperience").value(nullValue()))
			.andExpect(jsonPath("$.version").value(3));
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": null, \"version\": 3 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.goals").value(nullValue()))
			.andExpect(jsonPath("$.version").value(4));

		// Only the version: nothing changes.
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"version\": 4 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(4));
	}

	@Test
	void theCardIsSharedByAllCurrentTrainersOfTheParticipant() throws Exception {
		assign(trainee, trainer, false);
		assign(trainee, otherTrainer, false);
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"Redukcja 5 kg.\", \"version\": 0 }")
			.andExpect(status().isOk());

		perform(OTHER_TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.goals").value("Redukcja 5 kg."))
			.andExpect(jsonPath("$.updatedBy").value("Marek Nowak"))
			.andExpect(jsonPath("$.version").value(1));
		perform(OTHER_TRAINER_TOKEN, patch(card(trainee)), "{ \"sportExperience\": \"Rower.\", \"version\": 1 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.goals").value("Redukcja 5 kg."))
			.andExpect(jsonPath("$.updatedBy").value("Ewa Wiśniewska"));
	}

	@Test
	void aChangeBasedOnAStaleVersionIsRejected() throws Exception {
		assign(trainee, trainer, false);
		assign(trainee, otherTrainer, false);
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"Szpagat.\", \"version\": 0 }")
			.andExpect(status().isOk());

		// The other trainer still has the empty card open.
		perform(OTHER_TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"Mostek.\", \"version\": 0 }")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		perform(OTHER_TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"Mostek.\", \"version\": 7 }")
			.andExpect(status().isConflict());
		// A version for a card that does not exist yet is stale as well.
		assign(otherTrainee, trainer, false);
		perform(TRAINER_TOKEN, patch(card(otherTrainee)), "{ \"goals\": \"Mostek.\", \"version\": 1 }")
			.andExpect(status().isConflict());

		perform(TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(jsonPath("$.goals").value("Szpagat."))
			.andExpect(jsonPath("$.version").value(1));
		assertThat(jdbc.sql("SELECT count(*) FROM trainee_card WHERE customer_id = :id")
			.param("id", otherTrainee)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void textsAreValidated() throws Exception {
		assign(trainee, trainer, false);

		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"" + "a".repeat(2001) + "\", \"version\": 0 }")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("goals"))
			.andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \" " + "a".repeat(2000) + " \", \"version\": 0 }")
			.andExpect(status().isOk());
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"Szpagat.\" }")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("version"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
	}

	@Test
	void theCardIsHiddenFromOtherTrainersAndAfterTheCooperationEnds() throws Exception {
		assign(trainee, otherTrainer, true);
		grantHealthPermission(trainerAccount);

		perform(TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(TRAINER_TOKEN, patch(card(trainee)), "{ \"goals\": \"Szpagat.\", \"version\": 0 }")
			.andExpect(status().isNotFound());
		perform(TRAINER_TOKEN, post(healthNotes(trainee)), noteJson("INJURY", "Uraz kolana."))
			.andExpect(status().isNotFound());

		assign(trainee, trainer, true);
		perform(TRAINER_TOKEN, get(card(trainee)), null).andExpect(status().isOk());
		jdbc.sql("UPDATE staff_assignment SET ended_at = now() WHERE customer_id = :customer AND staff_id = :staff")
			.param("customer", trainee)
			.param("staff", trainer)
			.update();
		perform(TRAINER_TOKEN, get(card(trainee)), null).andExpect(status().isNotFound());
	}

	@Test
	void theCardOfAnInactiveTraineeIsNotAvailable() throws Exception {
		assign(trainee, trainer, false);
		jdbc.sql("UPDATE customer_profile SET status = 'INACTIVE' WHERE id = :id").param("id", trainee).update();

		perform(TRAINER_TOKEN, get(card(trainee)), null).andExpect(status().isNotFound());
		perform(TRAINER_TOKEN, get(card(UUID.randomUUID())), null).andExpect(status().isNotFound());
	}

	@Test
	void onlyTrainersKeepTraineeCards() throws Exception {
		assign(trainee, trainer, true);
		for (String token : new String[] { ADMIN_TOKEN, CLIENT_TOKEN }) {
			perform(token, get(card(trainee)), null)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
			perform(token, patch(card(trainee)), "{ \"goals\": \"Szpagat.\", \"version\": 0 }")
				.andExpect(status().isForbidden());
			perform(token, post(healthNotes(trainee)), noteJson("INJURY", "Uraz kolana."))
				.andExpect(status().isForbidden());
		}
		perform(NO_PROFILE_TRAINER_TOKEN, get(card(trainee)), null).andExpect(status().isNotFound());
	}

	@Test
	void healthNotesNeedThePermissionAndTheParticipantsConsent() throws Exception {
		UUID note = insertNote(trainee, "INJURY", "Uraz lewego kolana.", clientAccount);

		// The participant shared the data, but the trainer has no permission.
		assign(trainee, trainer, true);
		perform(TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.healthAccess").value("NO_PERMISSION"))
			.andExpect(jsonPath("$.healthNotes").value(nullValue()));
		perform(TRAINER_TOKEN, post(healthNotes(trainee)), noteJson("INJURY", "Uraz barku."))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		perform(TRAINER_TOKEN, get(healthNotes(trainee) + "/" + note), null).andExpect(status().isForbidden());
		perform(TRAINER_TOKEN, post(healthNotes(trainee) + "/" + note + "/archive"), null)
			.andExpect(status().isForbidden());

		// The trainer has the permission, but the participant has not shared the data with them.
		grantHealthPermission(otherTrainerAccount);
		assign(trainee, otherTrainer, false);
		perform(OTHER_TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.healthAccess").value("NOT_SHARED"))
			.andExpect(jsonPath("$.healthNotes").value(nullValue()));
		perform(OTHER_TRAINER_TOKEN, post(healthNotes(trainee)), noteJson("INJURY", "Uraz barku."))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("HEALTH_DATA_NOT_SHARED"));
		perform(OTHER_TRAINER_TOKEN, post(healthNotes(trainee) + "/" + note + "/archive"), null)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("HEALTH_DATA_NOT_SHARED"));

		// Both: the notes are shown.
		grantHealthPermission(trainerAccount);
		perform(TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.healthAccess").value("GRANTED"))
			.andExpect(jsonPath("$.healthNotes", hasSize(1)))
			.andExpect(jsonPath("$.healthNotes[0].id").value(note.toString()))
			.andExpect(jsonPath("$.healthNotes[0].kind").value("INJURY"))
			.andExpect(jsonPath("$.healthNotes[0].description").value("Uraz lewego kolana."))
			.andExpect(jsonPath("$.healthNotes[0].reportedBy").value("Tomasz Lewandowski"))
			.andExpect(jsonPath("$.healthNotes[0].reportedAt").isNotEmpty())
			.andExpect(jsonPath("$.healthNotes[0].archivedAt").value(nullValue()))
			.andExpect(jsonPath("$.healthNotes[0].archivedBy").value(nullValue()));
		assertThat(jdbc.sql("SELECT archived_at IS NULL FROM health_note WHERE id = :id")
			.param("id", note)
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void aTrainerAddsAndArchivesHealthNotes() throws Exception {
		assign(trainee, trainer, true);
		grantHealthPermission(trainerAccount);
		UUID older = insertNote(trainee, "CONDITION", "Astma wysiłkowa.", clientAccount);

		String body = perform(TRAINER_TOKEN, post(healthNotes(trainee)),
				noteJson("CONTRAINDICATION", "  Bez wiszenia przez 2 tygodnie.  "))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("CONTRAINDICATION"))
			.andExpect(jsonPath("$.description").value("Bez wiszenia przez 2 tygodnie."))
			.andExpect(jsonPath("$.reportedBy").value("Marek Nowak"))
			.andExpect(jsonPath("$.archivedAt").value(nullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID added = UUID.fromString(JsonPath.read(body, "$.id"));
		perform(TRAINER_TOKEN, get(healthNotes(trainee) + "/" + added), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.description").value("Bez wiszenia przez 2 tygodnie."));
		assertThat(jdbc.sql("SELECT reported_by FROM health_note WHERE id = :id")
			.param("id", added)
			.query(UUID.class)
			.single()).isEqualTo(trainerAccount);

		perform(TRAINER_TOKEN, post(healthNotes(trainee) + "/" + older + "/archive"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(older.toString()))
			.andExpect(jsonPath("$.archivedAt").isNotEmpty())
			.andExpect(jsonPath("$.archivedBy").value("Marek Nowak"))
			.andExpect(jsonPath("$.reportedBy").value("Tomasz Lewandowski"));
		String archivedAt = jdbc.sql("SELECT archived_at::text FROM health_note WHERE id = :id")
			.param("id", older)
			.query(String.class)
			.single();

		// A retry, or another trainer archiving it too, changes nothing.
		assign(trainee, otherTrainer, true);
		grantHealthPermission(otherTrainerAccount);
		perform(OTHER_TRAINER_TOKEN, post(healthNotes(trainee) + "/" + older + "/archive"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.archivedBy").value("Marek Nowak"));
		assertThat(jdbc.sql("SELECT archived_at::text FROM health_note WHERE id = :id")
			.param("id", older)
			.query(String.class)
			.single()).isEqualTo(archivedAt);

		// Current notes first, then the archived ones; nothing is deleted.
		perform(TRAINER_TOKEN, get(card(trainee)), null)
			.andExpect(jsonPath("$.healthNotes", hasSize(2)))
			.andExpect(jsonPath("$.healthNotes[0].id").value(added.toString()))
			.andExpect(jsonPath("$.healthNotes[1].id").value(older.toString()))
			.andExpect(jsonPath("$.healthNotes[1].archivedBy").value("Marek Nowak"));
	}

	@Test
	void aHealthNoteOfAnotherParticipantIsNotFound() throws Exception {
		assign(trainee, trainer, true);
		assign(otherTrainee, trainer, true);
		grantHealthPermission(trainerAccount);
		UUID otherNote = insertNote(otherTrainee, "INJURY", "Skręcona kostka.", trainerAccount);

		perform(TRAINER_TOKEN, get(healthNotes(trainee) + "/" + otherNote), null)
			.andExpect(status().isNotFound());
		perform(TRAINER_TOKEN, post(healthNotes(trainee) + "/" + otherNote + "/archive"), null)
			.andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT archived_at IS NULL FROM health_note WHERE id = :id")
			.param("id", otherNote)
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void aHealthNoteNeedsAKnownKindAndADescription() throws Exception {
		assign(trainee, trainer, true);
		grantHealthPermission(trainerAccount);

		perform(TRAINER_TOKEN, post(healthNotes(trainee)), "{ \"description\": \"Uraz kolana.\" }")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("kind"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		perform(TRAINER_TOKEN, post(healthNotes(trainee)), noteJson("INJURY", " \\u00a0 "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("description"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		perform(TRAINER_TOKEN, post(healthNotes(trainee)), noteJson("INJURY", "a".repeat(1001)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
		perform(TRAINER_TOKEN, post(healthNotes(trainee)), noteJson("BROKEN_LEG", "Złamana noga."))
			.andExpect(status().isBadRequest());
		assertThat(jdbc.sql("SELECT count(*) FROM health_note WHERE customer_id = :id")
			.param("id", trainee)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void theCreatedNoteHasItsLocation() throws Exception {
		assign(trainee, trainer, true);
		grantHealthPermission(trainerAccount);

		String location = perform(TRAINER_TOKEN, post(healthNotes(trainee)), noteJson("OTHER", "Alergia na lateks."))
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"))
			.andReturn()
			.getResponse()
			.getHeader("Location");
		perform(TRAINER_TOKEN, get(location), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("OTHER"));
	}

	private static String card(UUID customerId) {
		return TRAINEES + customerId + "/card";
	}

	private static String healthNotes(UUID customerId) {
		return TRAINEES + customerId + "/health-notes";
	}

	private static String noteJson(String kind, String description) {
		return "{ \"kind\": \"%s\", \"description\": \"%s\" }".formatted(kind, description);
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

	private UUID insertNote(UUID customerId, String kind, String description, UUID reportedBy) {
		return jdbc.sql("""
				INSERT INTO health_note (customer_id, kind, description, reported_by, created_at)
				VALUES (:customer, :kind, :description, :reportedBy, now() - interval '1 day')
				RETURNING id
				""")
			.param("customer", customerId)
			.param("kind", kind)
			.param("description", description)
			.param("reportedBy", reportedBy)
			.query(UUID.class)
			.single();
	}

	/** Adds an active account with the role; it can sign in with the token. */
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

	private UUID insertCustomer(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, first_name, last_name)
				VALUES (:accountId, :firstName, :lastName)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.query(UUID.class)
			.single();
	}

}
