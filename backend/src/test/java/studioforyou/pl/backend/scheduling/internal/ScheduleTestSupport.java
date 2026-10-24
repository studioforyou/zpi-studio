package studioforyou.pl.backend.scheduling.internal;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Studio data shared by the schedule tests: service types, rooms with their allowed services and
 * staff members. Firebase ID tokens are mocked at the {@link JwtDecoder},
 * as in {@code ResourceControllerTest}; the accounts behind them are real rows. Without a studio
 * row the studio's time zone is the column default, Europe/Warsaw.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class ScheduleTestSupport {

	static final ZoneId STUDIO_ZONE = ZoneId.of("Europe/Warsaw");

	static final String ADMIN_TOKEN = "schedule-admin-token";

	static final String TRAINER_TOKEN = "schedule-trainer-token";

	static final String TEST_EMAIL_DOMAIN = "@schedule.test";

	static final UUID POLE = UUID.fromString("a0000000-0000-0000-0000-000000000001");

	static final UUID PERSONAL = UUID.fromString("a0000000-0000-0000-0000-000000000002");

	static final UUID ARCHIVED_SERVICE = UUID.fromString("a0000000-0000-0000-0000-000000000003");

	static final UUID POLE_ROOM = UUID.fromString("b0000000-0000-0000-0000-000000000001");

	static final UUID GYM = UUID.fromString("b0000000-0000-0000-0000-000000000002");

	static final UUID CLOSED_ROOM = UUID.fromString("b0000000-0000-0000-0000-000000000003");

	static final UUID ANNA = UUID.fromString("c0000000-0000-0000-0000-000000000001");

	static final UUID MAREK = UUID.fromString("c0000000-0000-0000-0000-000000000002");

	static final UUID FORMER = UUID.fromString("c0000000-0000-0000-0000-000000000003");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private Filter springSecurityFilterChain;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	/** Sends every request as the studio administrator unless a request sets its own token. */
	MockMvc mockMvc;

	/** Sends requests without a token. */
	MockMvc anonymous;

	/** A Monday at least two weeks ahead, so every planned time is in the future. */
	LocalDate monday;

	@BeforeEach
	void setUpStudio() {
		cleanUp();
		UUID annaAccount = insertAccount("anna", "TRAINER", null);
		UUID marekAccount = insertAccount("marek", "TRAINER", null);
		UUID formerAccount = insertAccount("former", "TRAINER", null);
		insertAccount("admin", "ADMIN", ADMIN_TOKEN);
		insertAccount("trainer", "TRAINER", TRAINER_TOKEN);

		insertServiceType(POLE, "Pole dance", 10, true);
		insertServiceType(PERSONAL, "Personal training", 1, true);
		insertServiceType(ARCHIVED_SERVICE, "Archived service", 10, false);

		insertResource(POLE_ROOM, "Pole room", "ROOM", 10, true);
		insertResource(GYM, "Gym", "ROOM", 12, true);
		insertResource(CLOSED_ROOM, "Closed room", "ROOM", 8, false);
		allow(POLE, POLE_ROOM);
		allow(POLE, GYM);
		allow(PERSONAL, GYM);

		insertStaff(ANNA, annaAccount, "Anna", "Kowalska", "ACTIVE");
		insertStaff(MAREK, marekAccount, "Marek", "Nowak", "ACTIVE");
		insertStaff(FORMER, formerAccount, "Former", "Trainer", "INACTIVE");
		this.jdbcTemplate.update("INSERT INTO staff_qualification (staff_id, service_type_id) VALUES (?, ?)", ANNA, POLE);
		this.jdbcTemplate.update("INSERT INTO staff_qualification (staff_id, service_type_id) VALUES (?, ?)", MAREK,
				PERSONAL);

		this.monday = LocalDate.now(STUDIO_ZONE).plusWeeks(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
		this.anonymous = MockMvcBuilders.webAppContextSetup(this.context)
			.addFilters(this.springSecurityFilterChain)
			.build();
		this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
			.addFilters(this.springSecurityFilterChain)
			.defaultRequest(MockMvcRequestBuilders.get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
			.build();
	}

	@AfterEach
	void cleanUp() {
		this.jdbcTemplate.update("DELETE FROM event_staff");
		this.jdbcTemplate.update("DELETE FROM event_resource");
		this.jdbcTemplate.update("DELETE FROM schedule_event");
		this.jdbcTemplate.update("DELETE FROM recurrence_series");
		this.jdbcTemplate.update("DELETE FROM training_group");
		this.jdbcTemplate.update("DELETE FROM staff_time_off");
		this.jdbcTemplate.update("DELETE FROM trainer_availability");
		this.jdbcTemplate.update("DELETE FROM staff_qualification");
		this.jdbcTemplate.update("DELETE FROM service_type_resource");
		this.jdbcTemplate.update("DELETE FROM resource");
		this.jdbcTemplate.update("DELETE FROM service_type WHERE id IN (?, ?, ?)", POLE, PERSONAL, ARCHIVED_SERVICE);
		String testAccounts = "SELECT id FROM account WHERE email LIKE '%" + TEST_EMAIL_DOMAIN + "'";
		this.jdbcTemplate.update("DELETE FROM staff_profile WHERE account_id IN (" + testAccounts + ")");
		this.jdbcTemplate.update("DELETE FROM account_role WHERE account_id IN (" + testAccounts + ")");
		this.jdbcTemplate.update("DELETE FROM identity_link WHERE account_id IN (" + testAccounts + ")");
		this.jdbcTemplate.update("DELETE FROM account WHERE email LIKE '%" + TEST_EMAIL_DOMAIN + "'");
	}

	/** The instant of a studio local time on the given day. */
	static Instant at(LocalDate day, String localTime) {
		return day.atTime(LocalTime.parse(localTime)).atZone(STUDIO_ZONE).toInstant();
	}

	/** A one-off pole class from {@code start} to {@code end} on the day, run by Anna in the pole room. */
	String poleClass(LocalDate day, String start, String end) {
		return eventJson("CLASS", POLE, at(day, start), at(day, end), staff(ANNA, "LEAD"), room(POLE_ROOM, true));
	}

	static String eventJson(String kind, UUID serviceTypeId, Instant startsAt, Instant endsAt, String staff,
			String resources) {
		return """
				{"kind": "%s", "serviceTypeId": "%s", "startsAt": "%s", "endsAt": "%s",
				 "staff": [%s], "resources": [%s]}
				""".formatted(kind, serviceTypeId, startsAt, endsAt, staff, resources);
	}

	static String staff(UUID staffId, String role) {
		return """
				{"staffId": "%s", "role": "%s"}""".formatted(staffId, role);
	}

	static String room(UUID resourceId, boolean blocksWholeRoom) {
		return """
				{"resourceId": "%s", "blocksWholeRoom": %s}""".formatted(resourceId, blocksWholeRoom);
	}

	String createEvent(String json) throws Exception {
		return id(post("/api/v1/schedule-events", json).andExpect(status().isCreated()));
	}

	ResultActions post(String path, String json) throws Exception {
		return this.mockMvc
			.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions put(String path, String json) throws Exception {
		return this.mockMvc
			.perform(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions get(String path, String... params) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
		for (int i = 0; i + 1 < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		return this.mockMvc.perform(request);
	}

	static String id(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	static MockHttpServletRequestBuilder asTrainer(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TRAINER_TOKEN);
	}

	void setStatus(String eventId, String status) {
		this.jdbcTemplate.update("UPDATE schedule_event SET status = ? WHERE id = ?", status, UUID.fromString(eventId));
	}

	void insertTimeOff(UUID staffId, Instant startsAt, Instant endsAt, String status) {
		this.jdbcTemplate.update("""
				INSERT INTO staff_time_off (staff_id, kind, starts_at, ends_at, status)
				VALUES (?, 'VACATION', ?, ?, ?)
				""", staffId, java.sql.Timestamp.from(startsAt), java.sql.Timestamp.from(endsAt), status);
	}

	/**
	 * An active account with one role. With a token, it can sign in with that (mocked) Firebase token.
	 */
	private UUID insertAccount(String name, String roleCode, String token) {
		UUID id = this.jdbcTemplate.queryForObject(
				"INSERT INTO account (email, status) VALUES (?, 'ACTIVE') RETURNING id", UUID.class,
				name + TEST_EMAIL_DOMAIN);
		this.jdbcTemplate.update("INSERT INTO account_role (account_id, role_id) SELECT ?, id FROM role WHERE code = ?",
				id, roleCode);
		if (token != null) {
			String firebaseUid = "uid-schedule-" + name;
			this.jdbcTemplate.update(
					"INSERT INTO identity_link (account_id, provider, external_uid) VALUES (?, 'FIREBASE', ?)", id,
					firebaseUid);
			Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
			given(this.jwtDecoder.decode(token)).willReturn(jwt);
		}
		return id;
	}

	private void insertServiceType(UUID id, String name, int maxParticipants, boolean active) {
		this.jdbcTemplate.update("""
				INSERT INTO service_type (id, name, category, default_duration_minutes, max_participants, is_active)
				VALUES (?, ?, 'GROUP_CLASS', 60, ?, ?)
				""", id, name, maxParticipants, active);
	}

	private void insertResource(UUID id, String name, String kind, Integer maxPeople, boolean active) {
		this.jdbcTemplate.update("""
				INSERT INTO resource (id, name, kind, max_people, is_active) VALUES (?, ?, ?, ?, ?)
				""", id, name, kind, maxPeople, active);
	}

	private void allow(UUID serviceTypeId, UUID resourceId) {
		this.jdbcTemplate.update("INSERT INTO service_type_resource (service_type_id, resource_id) VALUES (?, ?)",
				serviceTypeId, resourceId);
	}

	private void insertStaff(UUID id, UUID accountId, String firstName, String lastName, String status) {
		this.jdbcTemplate.update("""
				INSERT INTO staff_profile (id, account_id, first_name, last_name, calendar_color, status)
				VALUES (?, ?, ?, ?, '#E91E63', ?)
				""", id, accountId, firstName, lastName, status);
	}

}
