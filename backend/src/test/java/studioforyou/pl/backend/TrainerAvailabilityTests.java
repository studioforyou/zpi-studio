package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * Trainers manage their own working hours and administrators manage anyone's. The JWT decoder
 * is mocked; authorization, validation and PostgreSQL writes run for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TrainerAvailabilityTests {

	private static final String PATH = "/api/v1/me/availability";

	private static final String TRAINER = "availability-trainer-token";

	private static final String OTHER_TRAINER = "availability-other-token";

	private static final String CLIENT = "availability-client-token";

	private static final String ADMIN = "availability-admin-token";

	private static final String NO_PROFILE = "availability-no-profile-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID trainerAccountId;

	private UUID trainerStaffId;

	private UUID otherStaffId;

	private LocalDate today;

	@BeforeEach
	void setUp() {
		String zone = jdbc.sql("SELECT time_zone FROM studio LIMIT 1").query(String.class).optional()
			.orElse("Europe/Warsaw");
		today = LocalDate.now(ZoneId.of(zone));
		trainerAccountId = addAccount("trainer", TRAINER, "TRAINER");
		trainerStaffId = addStaffProfile(trainerAccountId, "Anna", "Kowalska");
		otherStaffId = addStaffProfile(addAccount("other", OTHER_TRAINER, "TRAINER"), "Piotr", "Nowak");
		grantEditOwn(trainerAccountId);
		grantEditOwn(otherStaffAccount());
		addAccount("client", CLIENT, "CLIENT");
		addAccount("admin", ADMIN, "ADMIN");
		addAccount("missing", NO_PROFILE, "TRAINER");
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE 'availability-%@example.com'";
		String staff = "SELECT id FROM staff_profile WHERE account_id IN (" + accounts + ")";
		jdbc.sql("DELETE FROM account_permission_override WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM trainer_availability WHERE staff_id IN (" + staff + ")").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE 'availability-%@example.com'").update();
	}

	@Test
	void trainerAddsWeeklyHoursAndSingleDaysAndListsThemInOrder() throws Exception {
		LocalDate extraDay = today.plusDays(10);
		perform(TRAINER, post(PATH), weekly(4, "17:00", "21:00"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[0].type").value("WEEKLY"))
			.andExpect(jsonPath("$.items[0].weekday").value(4))
			.andExpect(jsonPath("$.items[0].date").isEmpty())
			.andExpect(jsonPath("$.items[0].startTime").value("17:00"))
			.andExpect(jsonPath("$.items[0].endTime").value("21:00"))
			.andExpect(jsonPath("$.items[0].validFrom").value(today.toString()))
			.andExpect(jsonPath("$.items[0].validTo").isEmpty());
		perform(TRAINER, post(PATH), oneOff(extraDay, "12:00", "14:00"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[0].type").value("ONE_OFF"))
			.andExpect(jsonPath("$.items[0].date").value(extraDay.toString()))
			.andExpect(jsonPath("$.items[0].weekday").value(extraDay.getDayOfWeek().getValue()))
			.andExpect(jsonPath("$.items[0].validTo").value(extraDay.toString()));
		perform(TRAINER, post(PATH), weekly(1, "16:00", "18:00")).andExpect(status().isCreated());
		perform(TRAINER, post(PATH), weekly(1, "08:00", "10:00")).andExpect(status().isCreated());

		perform(TRAINER, get(PATH), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].type").value(contains("WEEKLY", "WEEKLY", "WEEKLY", "ONE_OFF")))
			.andExpect(jsonPath("$.items[*].startTime").value(contains("08:00", "16:00", "17:00", "12:00")));
		perform(OTHER_TRAINER, get(PATH), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").value(empty()));
		assertThat(jdbc.sql("SELECT count(*) FROM trainer_availability WHERE staff_id = :id AND created_by IS NOT NULL")
			.param("id", trainerStaffId).query(Integer.class).single()).isEqualTo(4);
	}

	@Test
	void overlappingHoursOnTheSameDayAreRejected() throws Exception {
		String first = id(perform(TRAINER, post(PATH), weekly(2, "10:00", "12:00")).andExpect(status().isCreated()));
		perform(TRAINER, post(PATH), weekly(2, "11:00", "13:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AVAILABILITY_OVERLAP"))
			.andExpect(jsonPath("$.conflictingSlotId").value(first));
		LocalDate tuesday = nextWeekday(2);
		perform(TRAINER, post(PATH), oneOff(tuesday, "09:00", "10:30")).andExpect(status().isConflict());

		perform(TRAINER, post(PATH), weekly(2, "12:00", "14:00")).andExpect(status().isCreated());
		perform(TRAINER, post(PATH), weekly(3, "10:00", "12:00")).andExpect(status().isCreated());
		perform(TRAINER, post(PATH), oneOff(tuesday, "08:00", "10:00")).andExpect(status().isCreated());
		perform(OTHER_TRAINER, post(PATH), weekly(2, "10:00", "12:00")).andExpect(status().isCreated());
	}

	@Test
	void weeklyHoursWithSeparatePeriodsDoNotOverlap() throws Exception {
		LocalDate summerEnd = today.plusDays(30);
		perform(TRAINER, post(PATH), weekly(5, "10:00", "14:00", today.toString(), summerEnd.toString()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[0].validTo").value(summerEnd.toString()));
		perform(TRAINER, post(PATH), weekly(5, "12:00", "18:00", summerEnd.plusDays(1).toString(), null))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[0].validFrom").value(summerEnd.plusDays(1).toString()));
		perform(TRAINER, post(PATH), weekly(5, "12:00", "18:00", summerEnd.toString(), null))
			.andExpect(status().isConflict());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"weekdays\":[1],\"dates\":[\"%s\"],\"startTime\":\"10:00\",\"endTime\":\"11:00\"}",
			"{\"weekdays\":[],\"startTime\":\"10:00\",\"endTime\":\"11:00\"}",
			"{\"weekdays\":[1,null],\"startTime\":\"10:00\",\"endTime\":\"11:00\"}",
			"{\"startTime\":\"10:00\",\"endTime\":\"11:00\"}",
			"{\"weekdays\":[1],\"startTime\":\"11:00\",\"endTime\":\"11:00\"}",
			"{\"weekdays\":[1],\"startTime\":\"12:00\",\"endTime\":\"11:00\"}",
			"{\"weekdays\":[8],\"startTime\":\"10:00\",\"endTime\":\"11:00\"}",
			"{\"weekdays\":[0],\"startTime\":\"10:00\",\"endTime\":\"11:00\"}",
			"{\"weekdays\":[1],\"endTime\":\"11:00\"}",
			"{\"dates\":[\"%s\"],\"startTime\":\"10:00\",\"endTime\":\"11:00\",\"validTo\":\"%s\"}",
			"{\"weekdays\":[1],\"startTime\":\"10:00\",\"endTime\":\"11:00\",\"validFrom\":\"%s\",\"validTo\":\"%s\"}" })
	void invalidSlotsAreRejected(String body) throws Exception {
		String day = today.plusDays(3).toString();
		perform(TRAINER, post(PATH), body.formatted(day, day))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors").value(not(empty())));
		perform(TRAINER, get(PATH), null).andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void pastDaysCannotBeChanged() throws Exception {
		String yesterday = today.minusDays(1).toString();
		perform(TRAINER, post(PATH), oneOff(today.minusDays(1), "10:00", "11:00"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("dates"));
		perform(TRAINER, post(PATH), weekly(1, "10:00", "11:00", yesterday, null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("validFrom"));
		perform(TRAINER, post(PATH), "{\"weekdays\":[1],\"startTime\":\"10:00:30\",\"endTime\":\"11:00\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void hoursStartingTodayChangeInPlace() throws Exception {
		String id = id(perform(TRAINER, post(PATH), weekly(3, "10:00", "12:00")).andExpect(status().isCreated()));
		perform(TRAINER, patch(PATH + "/" + id), "{\"endTime\":\"13:30\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.startTime").value("10:00"))
			.andExpect(jsonPath("$.endTime").value("13:30"));
		String end = today.plusDays(60).toString();
		perform(TRAINER, patch(PATH + "/" + id), "{\"validTo\":\"" + end + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.validTo").value(end));
		perform(TRAINER, patch(PATH + "/" + id), "{\"validTo\":null}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.validTo").isEmpty());
		perform(TRAINER, patch(PATH + "/" + id), "{\"startTime\":\"14:00\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endTime"));
		assertThat(countRows(trainerStaffId)).isEqualTo(1);
	}

	@Test
	void changingHoursThatAlreadyAppliedKeepsTheirHistory() throws Exception {
		UUID past = insertRow(trainerStaffId, 2, "17:00", "21:00", today.minusDays(30), null);
		String newId = id(perform(TRAINER, patch(PATH + "/" + past), "{\"startTime\":\"18:00\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(not(past.toString())))
			.andExpect(jsonPath("$.startTime").value("18:00"))
			.andExpect(jsonPath("$.endTime").value("21:00"))
			.andExpect(jsonPath("$.validFrom").value(today.toString())));
		assertThat(validTo(past)).isEqualTo(today.minusDays(1));
		perform(TRAINER, get(PATH), null)
			.andExpect(jsonPath("$.items[*].id").value(contains(newId)));

		UUID other = insertRow(trainerStaffId, 4, "08:00", "10:00", today.minusDays(30), null);
		String end = today.plusDays(14).toString();
		perform(TRAINER, patch(PATH + "/" + other), "{\"validTo\":\"" + end + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(other.toString()))
			.andExpect(jsonPath("$.validFrom").value(today.minusDays(30).toString()));
		perform(TRAINER, patch(PATH + "/" + other), "{\"validTo\":\"" + today.minusDays(1) + "\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void singleDaysChangeTimesAndCanBecomeWeekly() throws Exception {
		LocalDate day = today.plusDays(2);
		String id = id(perform(TRAINER, post(PATH), oneOff(day, "10:00", "12:00"))
			.andExpect(status().isCreated()));
		perform(TRAINER, patch(PATH + "/" + id), "{\"startTime\":\"09:00\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("ONE_OFF"))
			.andExpect(jsonPath("$.startTime").value("09:00"));
		perform(TRAINER, patch(PATH + "/" + id), "{\"validTo\":\"" + day.minusDays(1) + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("validTo"));
		perform(TRAINER, patch(PATH + "/" + id), "{\"validTo\":null}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.type").value("WEEKLY"))
			.andExpect(jsonPath("$.date").isEmpty())
			.andExpect(jsonPath("$.weekday").value(day.getDayOfWeek().getValue()))
			.andExpect(jsonPath("$.validFrom").value(day.toString()))
			.andExpect(jsonPath("$.validTo").isEmpty());
	}

	@Test
	void sameHoursAreAddedOnSeveralDaysAllOrNothing() throws Exception {
		perform(TRAINER, post(PATH), "{\"weekdays\":[1,3,3],\"startTime\":\"08:00\",\"endTime\":\"10:00\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[*].weekday").value(contains(1, 3)));
		LocalDate first = today.plusDays(1);
		LocalDate second = today.plusDays(2);
		perform(TRAINER, post(PATH), "{\"dates\":[\"%s\",\"%s\"],\"startTime\":\"12:00\",\"endTime\":\"13:00\"}"
			.formatted(first, second))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.items[*].date").value(contains(first.toString(), second.toString())));
		assertThat(countRows(trainerStaffId)).isEqualTo(4);

		// Wednesday overlaps, so Friday is not added either.
		perform(TRAINER, post(PATH), "{\"weekdays\":[5,3],\"startTime\":\"09:00\",\"endTime\":\"11:00\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AVAILABILITY_OVERLAP"));
		assertThat(countRows(trainerStaffId)).isEqualTo(4);
		perform(TRAINER, post(PATH), "{\"dates\":[\"%s\"],\"startTime\":\"12:00\",\"endTime\":\"13:00\"}"
			.formatted(today.minusDays(1)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void weeklyHoursChangeOnASingleDate() throws Exception {
		UUID weekly = insertRow(trainerStaffId, 2, "17:00", "21:00", today.minusDays(30), null);
		LocalDate tuesday = nextWeekday(2);
		String day = id(perform(TRAINER, patch(PATH + "/" + weekly).param("date", tuesday.toString()),
				"{\"startTime\":\"18:00\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("ONE_OFF"))
			.andExpect(jsonPath("$.date").value(tuesday.toString()))
			.andExpect(jsonPath("$.startTime").value("18:00"))
			.andExpect(jsonPath("$.endTime").value("21:00")));

		assertThat(validTo(weekly)).isEqualTo(tuesday.minusDays(1));
		perform(TRAINER, get(PATH), null)
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[?(@.type == 'WEEKLY')].validFrom")
				.value(contains(today.minusDays(30).toString(), tuesday.plusDays(7).toString())))
			.andExpect(jsonPath("$.items[?(@.type == 'WEEKLY')].startTime").value(contains("17:00", "17:00")))
			.andExpect(jsonPath("$.items[2].id").value(day));

		perform(TRAINER, patch(PATH + "/" + weekly).param("date", tuesday.plusDays(1).toString()),
				"{\"startTime\":\"18:00\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("date"));
		// Repeating the changed day every week would overlap the weekly hours from the next week.
		perform(TRAINER, patch(PATH + "/" + day).param("date", tuesday.toString()), "{\"validTo\":null}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AVAILABILITY_OVERLAP"));
	}

	@Test
	void weeklyHoursAreRemovedOnASingleDate() throws Exception {
		LocalDate monday = nextWeekday(1);
		// Applied before: the earlier weeks stay, the later ones continue as a new slot.
		UUID past = insertRow(trainerStaffId, 1, "08:00", "10:00", today.minusDays(30), null);
		perform(TRAINER, delete(PATH + "/" + past).param("date", monday.toString()), null)
			.andExpect(status().isNoContent());
		assertThat(validTo(past)).isEqualTo(monday.minusDays(1));
		perform(TRAINER, get(PATH), null)
			.andExpect(jsonPath("$.items[*].validFrom")
				.value(contains(today.minusDays(30).toString(), monday.plusDays(7).toString())));

		// Starting on the date: the slot just starts a week later.
		String wednesday = id(perform(TRAINER, post(PATH),
				weekly(3, "08:00", "10:00", nextWeekday(3).toString(), null)).andExpect(status().isCreated()));
		perform(TRAINER, delete(PATH + "/" + wednesday).param("date", nextWeekday(3).toString()), null)
			.andExpect(status().isNoContent());
		perform(TRAINER, get(PATH), null)
			.andExpect(jsonPath("$.items[?(@.id == '" + wednesday + "')].validFrom")
				.value(contains(nextWeekday(3).plusDays(7).toString())));

		// Its only date: the slot is gone.
		LocalDate friday = nextWeekday(5);
		String once = id(perform(TRAINER, post(PATH),
				weekly(5, "08:00", "10:00", friday.toString(), friday.plusDays(3).toString()))
			.andExpect(status().isCreated()));
		perform(TRAINER, delete(PATH + "/" + once).param("date", friday.toString()), null)
			.andExpect(status().isNoContent());
		assertThat(countRows(trainerStaffId)).isEqualTo(3);

		perform(TRAINER, delete(PATH + "/" + past).param("date", monday.minusDays(7).toString()), null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("date"));
	}

	@Test
	void changesNeedThePermissionToEditOwnHours() throws Exception {
		jdbc.sql("DELETE FROM account_permission_override WHERE account_id = :id").param("id", trainerAccountId)
			.update();
		UUID slot = insertRow(trainerStaffId, 1, "08:00", "10:00", today, null);
		perform(TRAINER, get(PATH), null).andExpect(status().isOk());
		perform(TRAINER, post(PATH), weekly(2, "10:00", "11:00")).andExpect(status().isForbidden());
		perform(TRAINER, patch(PATH + "/" + slot), "{\"endTime\":\"11:00\"}").andExpect(status().isForbidden());
		perform(TRAINER, delete(PATH + "/" + slot), null).andExpect(status().isForbidden());
		assertThat(countRows(trainerStaffId)).isEqualTo(1);
	}

	@Test
	void removingHoursDeletesFutureOnesAndEndsPastOnes() throws Exception {
		String future = id(perform(TRAINER, post(PATH), weekly(1, "10:00", "12:00")).andExpect(status().isCreated()));
		UUID past = insertRow(trainerStaffId, 2, "17:00", "21:00", today.minusDays(30), null);

		perform(TRAINER, delete(PATH + "/" + future), null).andExpect(status().isNoContent());
		perform(TRAINER, delete(PATH + "/" + past), null).andExpect(status().isNoContent());

		assertThat(countRows(trainerStaffId)).isEqualTo(1);
		assertThat(validTo(past)).isEqualTo(today.minusDays(1));
		perform(TRAINER, get(PATH), null).andExpect(jsonPath("$.items").value(empty()));
		perform(TRAINER, delete(PATH + "/" + past), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void trainersCannotTouchSomeoneElsesHours() throws Exception {
		UUID others = insertRow(otherStaffId, 1, "10:00", "12:00", today, null);
		perform(TRAINER, patch(PATH + "/" + others), "{\"endTime\":\"13:00\"}").andExpect(status().isNotFound());
		perform(TRAINER, delete(PATH + "/" + others), null).andExpect(status().isNotFound());
		assertThat(countRows(otherStaffId)).isEqualTo(1);
	}

	@Test
	void ownAvailabilityIsForTrainersWithAStaffProfile() throws Exception {
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		perform(CLIENT, get(PATH), null)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		perform(ADMIN, post(PATH), weekly(1, "10:00", "11:00")).andExpect(status().isForbidden());
		perform(NO_PROFILE, get(PATH), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void adminsManageAnyStaffMembersHours() throws Exception {
		String path = "/api/v1/staff/" + otherStaffId + "/availability";
		String id = id(perform(ADMIN, post(path), weekly(6, "10:00", "14:00"))
			.andExpect(status().isCreated()));
		perform(OTHER_TRAINER, get(PATH), null).andExpect(jsonPath("$.items[*].id").value(contains(id)));
		perform(ADMIN, get(path), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[*].id").value(contains(id)));
		perform(ADMIN, patch(path + "/" + id), "{\"endTime\":\"15:00\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.endTime").value("15:00"));
		perform(ADMIN, delete(path + "/" + id), null).andExpect(status().isNoContent());
		perform(ADMIN, get(path), null).andExpect(jsonPath("$.items").value(empty()));

		String unknown = "/api/v1/staff/" + UUID.randomUUID() + "/availability";
		perform(ADMIN, post(unknown), weekly(6, "10:00", "14:00")).andExpect(status().isNotFound());
		perform(ADMIN, get(unknown), null).andExpect(status().isNotFound());
		perform(TRAINER, get(path), null).andExpect(status().isForbidden());
	}

	private static String weekly(int weekday, String start, String end) {
		return "{\"weekdays\":[%d],\"startTime\":\"%s\",\"endTime\":\"%s\"}".formatted(weekday, start, end);
	}

	private static String weekly(int weekday, String start, String end, String validFrom, String validTo) {
		return "{\"weekdays\":[%d],\"startTime\":\"%s\",\"endTime\":\"%s\",\"validFrom\":\"%s\",\"validTo\":%s}"
			.formatted(weekday, start, end, validFrom, validTo == null ? "null" : "\"" + validTo + "\"");
	}

	private static String oneOff(LocalDate date, String start, String end) {
		return "{\"dates\":[\"%s\"],\"startTime\":\"%s\",\"endTime\":\"%s\"}".formatted(date, start, end);
	}

	/** The first day from tomorrow on that falls on the ISO weekday. */
	private LocalDate nextWeekday(int weekday) {
		LocalDate day = today.plusDays(1);
		while (day.getDayOfWeek().getValue() != weekday) {
			day = day.plusDays(1);
		}
		return day;
	}

	private static String id(ResultActions result) throws Exception {
		String body = result.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, body.contains("\"items\"") ? "$.items[0].id" : "$.id");
	}

	private int countRows(UUID staffId) {
		return jdbc.sql("SELECT count(*) FROM trainer_availability WHERE staff_id = :id")
			.param("id", staffId).query(Integer.class).single();
	}

	private LocalDate validTo(UUID slotId) {
		return jdbc.sql("SELECT valid_to FROM trainer_availability WHERE id = :id")
			.param("id", slotId).query(LocalDate.class).single();
	}

	private UUID insertRow(UUID staffId, int weekday, String start, String end, LocalDate validFrom,
			LocalDate validTo) {
		return jdbc.sql("""
				INSERT INTO trainer_availability (staff_id, weekday, local_start_time, local_end_time, valid_from, valid_to)
				VALUES (:staffId, :weekday, CAST(:start AS time), CAST(:end AS time), :validFrom, :validTo)
				RETURNING id
				""")
			.param("staffId", staffId).param("weekday", weekday).param("start", start).param("end", end)
			.param("validFrom", validFrom).param("validTo", validTo).query(UUID.class).single();
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private UUID addAccount(String name, String token, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", "availability-" + name + "@example.com").query(UUID.class).single();
		String uid = "uid-availability-" + name;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
				.param("id", id).param("role", role).update();
		}
		given(jwtDecoder.decode(token))
			.willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID otherStaffAccount() {
		return jdbc.sql("SELECT account_id FROM staff_profile WHERE id = :id").param("id", otherStaffId)
			.query(UUID.class).single();
	}

	private void grantEditOwn(UUID accountId) {
		jdbc.sql("""
				INSERT INTO account_permission_override (account_id, permission_id, effect, granted_by)
				SELECT :id, id, 'GRANT', :id FROM permission WHERE code = 'AVAILABILITY_EDIT_OWN'
				""").param("id", accountId).update();
	}

	private UUID addStaffProfile(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name)
				VALUES (:accountId, :firstName, :lastName) RETURNING id
				""")
			.param("accountId", accountId).param("firstName", firstName).param("lastName", lastName)
			.query(UUID.class).single();
	}

}
