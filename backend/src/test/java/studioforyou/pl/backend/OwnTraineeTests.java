package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Trainers assign participants to themselves and end those assignments. Firebase ID tokens are
 * mocked at the {@link JwtDecoder}, as in the authentication tests.
 * <p>
 * The tests are not transactional, so requests run as in production; the data is removed after
 * each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnTraineeTests {

	private static final String TRAINEES = "/api/v1/me/trainees";

	private static final String TRAINER_TOKEN = "trainer-token";

	private static final String OTHER_TRAINER_TOKEN = "other-trainer-token";

	private static final String NO_PROFILE_TRAINER_TOKEN = "no-profile-trainer-token";

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String CLIENT_TOKEN = "client-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID trainerAccount;

	private UUID trainer;

	private UUID otherTrainer;

	private UUID adult;

	private UUID child;

	private UUID inactiveAdult;

	@BeforeEach
	void setUp() {
		trainerAccount = addAccount("trainer@example.com", "uid-trainer", "TRAINER", TRAINER_TOKEN);
		trainer = insertStaff(trainerAccount, "Marek", "Nowak", "ACTIVE");
		otherTrainer = insertStaff(
				addAccount("trainer2@example.com", "uid-trainer2", "TRAINER", OTHER_TRAINER_TOKEN), "Ewa",
				"Wiśniewska", "ACTIVE");
		addAccount("trainer3@example.com", "uid-trainer3", "TRAINER", NO_PROFILE_TRAINER_TOKEN);
		insertStaff(addAccount("admin@example.com", "uid-admin", "ADMIN", ADMIN_TOKEN), "Beata", "Lis", "ACTIVE");
		addAccount("client@example.com", "uid-client", "CLIENT", CLIENT_TOKEN);
		adult = insertCustomer("ADULT", "Katarzyna", "Zielińska", "ACTIVE");
		child = insertCustomer("CHILD", "Zosia", "Dąbrowska", "ACTIVE");
		inactiveAdult = insertCustomer("ADULT", "Adam", "Mazur", "INACTIVE");
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE '%@example.com'";
		jdbc.sql("DELETE FROM staff_assignment WHERE assigned_by IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)")
			.param("ids", List.of(adult, child, inactiveAdult))
			.update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%@example.com'").update();
	}

	@Test
	void aTrainerAssignsParticipantsToThemselves() throws Exception {
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(adult))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", TRAINEES + "/" + adult))
			.andExpect(jsonPath("$.customerId").value(adult.toString()))
			.andExpect(jsonPath("$.kind").value("ADULT"))
			.andExpect(jsonPath("$.firstName").value("Katarzyna"))
			.andExpect(jsonPath("$.lastName").value("Zielińska"))
			.andExpect(jsonPath("$.displayName").value("Katarzyna Zielińska"))
			.andExpect(jsonPath("$.isActive").value(true))
			.andExpect(jsonPath("$.assignedBy").value(trainerAccount.toString()))
			.andExpect(jsonPath("$.assignedAt").isNotEmpty());
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(child)).andExpect(status().isCreated());

		perform(TRAINER_TOKEN, get(TRAINEES), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].customerId").value(child.toString()))
			.andExpect(jsonPath("$.items[1].customerId").value(adult.toString()));
		perform(TRAINER_TOKEN, get(TRAINEES + "/" + adult), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Katarzyna Zielińska"));

		Map<String, Object> stored = jdbc.sql("""
				SELECT staff_id, scope, can_view_health, assigned_by, ended_at
				FROM staff_assignment WHERE customer_id = :customer
				""")
			.param("customer", adult)
			.query()
			.singleRow();
		assertThat(stored).containsEntry("staff_id", trainer)
			.containsEntry("scope", "PERSONAL_TRAINING")
			.containsEntry("can_view_health", false)
			.containsEntry("assigned_by", trainerAccount)
			.containsEntry("ended_at", null);
	}

	@Test
	void theAdministratorSeesTheTrainerAssignedByThemselves() throws Exception {
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(adult)).andExpect(status().isCreated());

		perform(ADMIN_TOKEN, get("/api/v1/customers/" + adult + "/trainers"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].staffId").value(trainer.toString()))
			.andExpect(jsonPath("$.items[0].assignedBy").value(trainerAccount.toString()));
	}

	@Test
	void aTrainerSeesOnlyTheirOwnTrainees() throws Exception {
		perform(OTHER_TRAINER_TOKEN, post(TRAINEES), assignJson(adult)).andExpect(status().isCreated());

		perform(TRAINER_TOKEN, get(TRAINEES), null).andExpect(jsonPath("$.items", hasSize(0)));
		perform(TRAINER_TOKEN, get(TRAINEES + "/" + adult), null).andExpect(status().isNotFound());
		perform(TRAINER_TOKEN, delete(TRAINEES + "/" + adult), null).andExpect(status().isNotFound());

		// Several trainers can work with the same participant.
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(adult)).andExpect(status().isCreated());
		assertThat(jdbc.sql("SELECT staff_id FROM staff_assignment WHERE customer_id = :id AND ended_at IS NULL")
			.param("id", adult)
			.query(UUID.class)
			.list()).containsExactlyInAnyOrder(trainer, otherTrainer);
	}

	@Test
	void aParticipantCannotBeAssignedTwiceAtTheSameTime() throws Exception {
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(adult)).andExpect(status().isCreated());
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(adult))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TRAINER_ALREADY_ASSIGNED"));
	}

	@Test
	void endingTheCooperationKeepsItAsHistory() throws Exception {
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(adult)).andExpect(status().isCreated());

		perform(TRAINER_TOKEN, delete(TRAINEES + "/" + adult), null).andExpect(status().isNoContent());

		perform(TRAINER_TOKEN, get(TRAINEES), null).andExpect(jsonPath("$.items", hasSize(0)));
		perform(TRAINER_TOKEN, delete(TRAINEES + "/" + adult), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		assertThat(jdbc.sql("SELECT count(*) FROM staff_assignment WHERE customer_id = :id AND ended_at IS NOT NULL")
			.param("id", adult)
			.query(Long.class)
			.single()).isEqualTo(1);

		// The trainer can start working with the participant again later.
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(adult)).andExpect(status().isCreated());
	}

	@Test
	void theParticipantMustExistAndBeActive() throws Exception {
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(inactiveAdult))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ACTIVE"));
		perform(TRAINER_TOKEN, post(TRAINEES), assignJson(UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void theParticipantIsRequired() throws Exception {
		perform(TRAINER_TOKEN, post(TRAINEES), "{}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("customerId"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
	}

	@Test
	void aTrainerWithoutAStaffProfileHasNoTrainees() throws Exception {
		perform(NO_PROFILE_TRAINER_TOKEN, get(TRAINEES), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(NO_PROFILE_TRAINER_TOKEN, post(TRAINEES), assignJson(adult)).andExpect(status().isNotFound());
	}

	@Test
	void onlyTrainersHaveTrainees() throws Exception {
		for (String token : new String[] { ADMIN_TOKEN, CLIENT_TOKEN }) {
			perform(token, post(TRAINEES), assignJson(adult))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
			perform(token, get(TRAINEES), null).andExpect(status().isForbidden());
			perform(token, delete(TRAINEES + "/" + adult), null).andExpect(status().isForbidden());
		}
	}

	@Test
	void aTrainerSearchesParticipants() throws Exception {
		perform(TRAINER_TOKEN, get("/api/v1/customers?query=zielińska"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(adult.toString()));
		perform(CLIENT_TOKEN, get("/api/v1/customers"), null).andExpect(status().isForbidden());
	}

	private static String assignJson(UUID customerId) {
		return "{ \"customerId\": \"%s\" }".formatted(customerId);
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	/** Adds an active account with the role; it can sign in only when a token is given. */
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
		if (token != null) {
			Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
			given(jwtDecoder.decode(token)).willReturn(jwt);
		}
		return id;
	}

	private UUID insertStaff(UUID accountId, String firstName, String lastName, String status) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name, status)
				VALUES (:accountId, :firstName, :lastName, :status)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("status", status)
			.query(UUID.class)
			.single();
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

}
