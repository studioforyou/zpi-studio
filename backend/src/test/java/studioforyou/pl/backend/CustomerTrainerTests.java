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
 * Administrators assign trainers to participants and end those assignments. Firebase ID tokens
 * are mocked at the {@link JwtDecoder}, as in the authentication tests.
 * <p>
 * The tests are not transactional, so requests run as in production; the data is removed after
 * each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerTrainerTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	private static final String CLIENT_TOKEN = "client-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminAccount;

	private UUID adminStaff;

	private UUID trainer;

	private UUID otherTrainer;

	private UUID inactiveTrainer;

	private UUID adult;

	private UUID child;

	private UUID inactiveAdult;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "uid-admin", "ADMIN", ADMIN_TOKEN);
		adminStaff = insertStaff(adminAccount, "Beata", "Lis", "ACTIVE");
		trainer = insertStaff(addAccount("trainer@example.com", "uid-trainer", "TRAINER", TRAINER_TOKEN), "Marek",
				"Nowak", "ACTIVE");
		otherTrainer = insertStaff(addAccount("trainer2@example.com", "uid-trainer2", "TRAINER", null), "Ewa",
				"Wiśniewska", "ACTIVE");
		inactiveTrainer = insertStaff(addAccount("trainer3@example.com", "uid-trainer3", "TRAINER", null), "Jan",
				"Kowalczyk", "INACTIVE");
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
	void adminAssignsSeveralTrainersToAParticipant() throws Exception {
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(trainer))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", trainersOf(adult) + "/" + trainer))
			.andExpect(jsonPath("$.staffId").value(trainer.toString()))
			.andExpect(jsonPath("$.firstName").value("Marek"))
			.andExpect(jsonPath("$.lastName").value("Nowak"))
			.andExpect(jsonPath("$.displayName").value("Marek Nowak"))
			.andExpect(jsonPath("$.isActive").value(true))
			.andExpect(jsonPath("$.assignedBy").value(adminAccount.toString()))
			.andExpect(jsonPath("$.assignedAt").isNotEmpty());
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(otherTrainer)).andExpect(status().isCreated());

		perform(ADMIN_TOKEN, get(trainersOf(adult)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].staffId").value(trainer.toString()))
			.andExpect(jsonPath("$.items[1].staffId").value(otherTrainer.toString()));
		perform(ADMIN_TOKEN, get(trainersOf(adult) + "/" + otherTrainer), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Ewa Wiśniewska"));

		Map<String, Object> stored = jdbc.sql("""
				SELECT scope, can_view_health, assigned_by, ended_at
				FROM staff_assignment WHERE customer_id = :customer AND staff_id = :staff
				""")
			.param("customer", adult)
			.param("staff", trainer)
			.query()
			.singleRow();
		assertThat(stored).containsEntry("scope", "PERSONAL_TRAINING")
			.containsEntry("can_view_health", false)
			.containsEntry("assigned_by", adminAccount)
			.containsEntry("ended_at", null);
	}

	@Test
	void aChildCanHaveATrainer() throws Exception {
		perform(ADMIN_TOKEN, post(trainersOf(child)), assignJson(trainer)).andExpect(status().isCreated());
	}

	@Test
	void aTrainerCannotBeAssignedTwiceAtTheSameTime() throws Exception {
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(trainer)).andExpect(status().isCreated());
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(trainer))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TRAINER_ALREADY_ASSIGNED"));
	}

	@Test
	void unassigningKeepsTheAssignmentAsHistory() throws Exception {
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(trainer)).andExpect(status().isCreated());

		perform(ADMIN_TOKEN, delete(trainersOf(adult) + "/" + trainer), null).andExpect(status().isNoContent());

		perform(ADMIN_TOKEN, get(trainersOf(adult)), null).andExpect(jsonPath("$.items", hasSize(0)));
		perform(ADMIN_TOKEN, get(trainersOf(adult) + "/" + trainer), null).andExpect(status().isNotFound());
		perform(ADMIN_TOKEN, delete(trainersOf(adult) + "/" + trainer), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		assertThat(jdbc.sql("SELECT count(*) FROM staff_assignment WHERE customer_id = :id AND ended_at IS NOT NULL")
			.param("id", adult)
			.query(Long.class)
			.single()).isEqualTo(1);

		// The same trainer can be assigned again later.
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(trainer)).andExpect(status().isCreated());
	}

	@Test
	void onlyActiveTrainersCanBeAssigned() throws Exception {
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(adminStaff))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STAFF_NOT_TRAINER"));
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(inactiveTrainer))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STAFF_UNAVAILABLE"));
		perform(ADMIN_TOKEN, post(trainersOf(adult)), assignJson(UUID.randomUUID()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STAFF_UNAVAILABLE"));
	}

	@Test
	void onlyActiveTrainersAreOfferedForAssignment() throws Exception {
		perform(ADMIN_TOKEN, get("/api/v1/customers/assignable-trainers"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].staffId").value(trainer.toString()))
			.andExpect(jsonPath("$.items[0].displayName").value("Marek Nowak"))
			.andExpect(jsonPath("$.items[1].staffId").value(otherTrainer.toString()));
		perform(TRAINER_TOKEN, get("/api/v1/customers/assignable-trainers"), null).andExpect(status().isForbidden());
	}

	@Test
	void theParticipantMustExistAndBeActive() throws Exception {
		perform(ADMIN_TOKEN, post(trainersOf(inactiveAdult)), assignJson(trainer))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ACTIVE"));
		perform(ADMIN_TOKEN, post(trainersOf(UUID.randomUUID())), assignJson(trainer))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(ADMIN_TOKEN, get(trainersOf(UUID.randomUUID())), null).andExpect(status().isNotFound());
	}

	@Test
	void theTrainerIsRequired() throws Exception {
		perform(ADMIN_TOKEN, post(trainersOf(adult)), "{}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("staffId"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
	}

	@Test
	void onlyAnAdminCanManageTrainers() throws Exception {
		for (String token : new String[] { TRAINER_TOKEN, CLIENT_TOKEN }) {
			perform(token, post(trainersOf(adult)), assignJson(trainer))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
			perform(token, get(trainersOf(adult)), null).andExpect(status().isForbidden());
			perform(token, delete(trainersOf(adult) + "/" + trainer), null).andExpect(status().isForbidden());
		}
	}

	private static String trainersOf(UUID customer) {
		return "/api/v1/customers/" + customer + "/trainers";
	}

	private static String assignJson(UUID staffId) {
		return "{ \"staffId\": \"%s\" }".formatted(staffId);
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
