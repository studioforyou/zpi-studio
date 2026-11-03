package studioforyou.pl.backend;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Trainers see the details and participants of the events they run or assist. Firebase ID tokens
 * are mocked at the {@link JwtDecoder}, as in the authentication tests.
 * <p>
 * The tests are not transactional, so requests run as in production; the data is removed after
 * each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnEventParticipantTests {

	private static final String EVENTS = "/api/v1/me/schedule-events/";

	private static final String LEAD_TOKEN = "roster-lead-token";

	private static final String ASSISTANT_TOKEN = "roster-assistant-token";

	private static final String OTHER_TRAINER_TOKEN = "roster-other-trainer-token";

	private static final String NO_PROFILE_TRAINER_TOKEN = "roster-no-profile-token";

	private static final String ADMIN_TOKEN = "roster-admin-token";

	private static final String CLIENT_TOKEN = "roster-client-token";

	private static final String EMAIL_DOMAIN = "@roster.test";

	private static final String EVENT_TITLE = "roster-test-class";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID clientAccount;

	private UUID lead;

	private UUID assistant;

	private Instant startsAt;

	@BeforeEach
	void setUp() {
		lead = insertStaff(addAccount("lead", "TRAINER", LEAD_TOKEN), "Anna", "Kowalska");
		assistant = insertStaff(addAccount("assistant", "TRAINER", ASSISTANT_TOKEN), "Marek", "Nowak");
		insertStaff(addAccount("other", "TRAINER", OTHER_TRAINER_TOKEN), "Ewa", "Lis");
		addAccount("no-profile", "TRAINER", NO_PROFILE_TRAINER_TOKEN);
		insertStaff(addAccount("admin", "ADMIN", ADMIN_TOKEN), "Beata", "Wrona");
		clientAccount = addAccount("client", "CLIENT", CLIENT_TOKEN);
		startsAt = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'";
		String events = "SELECT id FROM schedule_event WHERE title = '" + EVENT_TITLE + "'";
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM event_staff WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM schedule_event WHERE title = '" + EVENT_TITLE + "'").update();
		jdbc.sql("DELETE FROM customer_profile WHERE last_name LIKE 'roster-%'").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'").update();
	}

	@Test
	void theLeadSeesActiveParticipantsSortedByLastName() throws Exception {
		UUID event = insertEvent("CLASS", "PUBLIC");
		assign(event, lead, "LEAD");
		assign(event, assistant, "ASSISTANT");
		// Polish order: Ć after C and Ż after Z, unlike plain code point order.
		UUID zak = insertCustomer("ADULT", "Piotr", "roster-Żak", LocalDate.of(1990, 5, 1));
		UUID cwik = insertCustomer("CHILD", "Zosia", "roster-Ćwik", LocalDate.of(2016, 9, 12));
		UUID nowak = insertCustomer("ADULT", "Ola", "roster-Nowak", null);
		UUID cancelled = insertCustomer("ADULT", "Jan", "roster-Adamski", null);
		UUID zakReservation = reserve(event, zak, "ACTIVE");
		reserve(event, cwik, "ACTIVE");
		reserve(event, nowak, "ACTIVE");
		reserve(event, cancelled, "CANCELLED");

		participants(LEAD_TOKEN, event).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].lastName").value(contains("roster-Ćwik", "roster-Nowak", "roster-Żak")))
			.andExpect(jsonPath("$.items[0].kind").value("CHILD"))
			.andExpect(jsonPath("$.items[0].customerId").value(cwik.toString()))
			.andExpect(jsonPath("$.items[0].displayName").value("Zosia roster-Ćwik"))
			.andExpect(jsonPath("$.items[0].birthDate").value("2016-09-12"))
			.andExpect(jsonPath("$.items[1].birthDate").value(nullValue()))
			.andExpect(jsonPath("$.items[2].reservationId").value(zakReservation.toString()))
			.andExpect(jsonPath("$.items[2].bookedAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].phone").doesNotExist())
			.andExpect(jsonPath("$.items[0].emergencyPhone").doesNotExist());
		participants(ASSISTANT_TOKEN, event).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3));
	}

	@Test
	void aTrainerSeesTheirEventWhateverItsVisibilityOrStatus() throws Exception {
		UUID event = insertEvent("CLASS", "PRIVATE");
		jdbc.sql("UPDATE schedule_event SET status = 'CANCELLED' WHERE id = :id").param("id", event).update();
		assign(event, assistant, "ASSISTANT");

		perform(ASSISTANT_TOKEN, EVENTS + event).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(event.toString()))
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.staff[0].role").value("ASSISTANT"));
		participants(ASSISTANT_TOKEN, event).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void otherTrainersEventsBlocksAndUnknownEventsAreNotFound() throws Exception {
		UUID event = insertEvent("CLASS", "PUBLIC");
		assign(event, lead, "LEAD");
		reserve(event, insertCustomer("ADULT", "Ola", "roster-Nowak", null), "ACTIVE");
		UUID block = insertEvent("BLOCK", "PRIVATE");
		assign(block, lead, "LEAD");

		for (UUID hidden : new UUID[] { block, UUID.randomUUID() }) {
			perform(LEAD_TOKEN, EVENTS + hidden).andExpect(status().isNotFound());
			participants(LEAD_TOKEN, hidden).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		perform(OTHER_TRAINER_TOKEN, EVENTS + event).andExpect(status().isNotFound());
		participants(OTHER_TRAINER_TOKEN, event).andExpect(status().isNotFound());
		perform(NO_PROFILE_TRAINER_TOKEN, EVENTS + event).andExpect(status().isNotFound());
		participants(NO_PROFILE_TRAINER_TOKEN, event).andExpect(status().isNotFound());
	}

	@Test
	void participantsAreOnlyForTrainers() throws Exception {
		UUID event = insertEvent("CLASS", "PUBLIC");
		assign(event, lead, "LEAD");

		for (String token : new String[] { ADMIN_TOKEN, CLIENT_TOKEN }) {
			perform(token, EVENTS + event).andExpect(status().isForbidden());
			participants(token, event).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		}
		mvc.perform(get(EVENTS + event + "/participants")).andExpect(status().isUnauthorized());
	}

	private ResultActions participants(String token, UUID eventId) throws Exception {
		return perform(token, EVENTS + eventId + "/participants");
	}

	private ResultActions perform(String token, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", "Bearer " + token));
	}

	/** Adds an active account with the role, signed in with the token. */
	private UUID addAccount(String name, String roleCode, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + EMAIL_DOMAIN)
			.query(UUID.class)
			.single();
		String uid = "roster-" + id;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", uid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id)
			.param("code", roleCode)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build();
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

	private UUID insertCustomer(String kind, String firstName, String lastName, LocalDate birthDate) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, birth_date)
				VALUES (:kind, :firstName, :lastName, :birthDate)
				RETURNING id
				""")
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("birthDate", birthDate, java.sql.Types.DATE)
			.query(UUID.class)
			.single();
	}

	private UUID insertEvent(String kind, String visibility) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, title, starts_at, ends_at, capacity, visibility)
				VALUES (:kind, :title, :startsAt, :endsAt, 12, :visibility)
				RETURNING id
				""")
			.param("kind", kind)
			.param("title", EVENT_TITLE)
			.param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
			.param("endsAt", startsAt.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
			.param("visibility", visibility)
			.query(UUID.class)
			.single();
	}

	private void assign(UUID eventId, UUID staffId, String role) {
		jdbc.sql("INSERT INTO event_staff (event_id, staff_id, role) VALUES (:event, :staff, :role)")
			.param("event", eventId)
			.param("staff", staffId)
			.param("role", role)
			.update();
	}

	private UUID reserve(UUID eventId, UUID customerId, String status) {
		return jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status)
				VALUES (:event, :customer, :bookedBy, :status)
				RETURNING id
				""")
			.param("event", eventId)
			.param("customer", customerId)
			.param("bookedBy", clientAccount)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

}
