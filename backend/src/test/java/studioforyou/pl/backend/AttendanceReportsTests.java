package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.jspecify.annotations.Nullable;
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
 * Administrators see attendance and occupancy of group classes (#164) and no-shows and late
 * cancellations (#166). The data lies in March 2025, away from the current dates other tests use.
 * Without a studio row the studio's time zone is Europe/Warsaw (UTC+1 in March until the 30th) and
 * the studio's cancellation rules are the built-in ones: free cancellation until 12 hours before,
 * a late cancellation forfeits the entry.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AttendanceReportsTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminAccount;

	private final List<UUID> services = new ArrayList<>();

	private final List<UUID> profiles = new ArrayList<>();

	private UUID pole;

	private UUID workshop;

	private UUID personal;

	private UUID group;

	private UUID series;

	private UUID trainer;

	private UUID room;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "ADMIN");
		signIn(adminAccount, "uid-admin", ADMIN_TOKEN);
		UUID trainerAccount = addAccount("trainer@example.com", "TRAINER");
		signIn(trainerAccount, "uid-trainer", TRAINER_TOKEN);
		trainer = jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:account, 'Ewa', 'Trenerka')
				RETURNING id
				""").param("account", trainerAccount).query(UUID.class).single();
		room = jdbc.sql("INSERT INTO resource (name, kind) VALUES ('Sala raportów', 'ROOM') RETURNING id")
			.query(UUID.class)
			.single();
		pole = insertService("Pole dance", "GROUP_CLASS", "#E91E63");
		workshop = insertService("Warsztaty", "WORKSHOP", "#4CAF50");
		personal = insertService("Trening personalny", "PERSONAL_TRAINING", "#FF9800");
		// Pole dance has its own rules: free cancellation until 24 hours before, the entry is returned.
		jdbc.sql("""
				INSERT INTO booking_policy (scope, service_type_id, free_cancel_hours, late_cancel_forfeits_entry)
				VALUES ('SERVICE_TYPE', :service, 24, FALSE)
				""").param("service", pole).update();
		group = jdbc.sql("INSERT INTO training_group (name, service_type_id) VALUES ('Pole od podstaw', :service) RETURNING id")
			.param("service", pole)
			.query(UUID.class)
			.single();
		series = jdbc.sql("""
				INSERT INTO recurrence_series (service_type_id, training_group_id, weekday, local_start_time,
				                               duration_minutes, starts_on)
				VALUES (:service, :group, 2, TIME '18:00', 60, DATE '2025-03-01')
				RETURNING id
				""").param("service", pole).param("group", group).query(UUID.class).single();
	}

	@AfterEach
	void tearDown() {
		deleteAmong("DELETE FROM waitlist_entry WHERE customer_id IN (:ids)", profiles);
		deleteAmong("DELETE FROM reservation WHERE customer_id IN (:ids)", profiles);
		deleteAmong("""
				DELETE FROM event_staff WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id IN (:ids))
				""", services);
		deleteAmong("""
				DELETE FROM event_resource WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id IN (:ids))
				""", services);
		deleteAmong("DELETE FROM schedule_event WHERE service_type_id IN (:ids)", services);
		deleteAmong("DELETE FROM recurrence_series WHERE service_type_id IN (:ids)", services);
		deleteAmong("DELETE FROM training_group WHERE service_type_id IN (:ids)", services);
		deleteAmong("DELETE FROM booking_policy WHERE service_type_id IN (:ids)", services);
		deleteAmong("DELETE FROM service_type WHERE id IN (:ids)", services);
		deleteAmong("DELETE FROM customer_profile WHERE id IN (:ids)", profiles);
		jdbc.sql("DELETE FROM resource WHERE id = :id").param("id", room).update();
		jdbc.sql("DELETE FROM staff_profile WHERE id = :id").param("id", trainer).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%@example.com'").update();
		services.clear();
		profiles.clear();
	}

	private void deleteAmong(String sql, List<UUID> ids) {
		if (!ids.isEmpty()) {
			jdbc.sql(sql).param("ids", ids).update();
		}
	}

	@Test
	void reportsAttendanceAndOccupancyPerClass() throws Exception {
		March march = new March();

		report("/api/v1/reports/class-attendance", "2025-03-01", "2025-03-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.from").value("2025-03-01"))
			.andExpect(jsonPath("$.to").value("2025-03-31"))
			// The cancelled class, the personal session and April's class are left out.
			.andExpect(jsonPath("$.totals.events").value(4))
			.andExpect(jsonPath("$.totals.booked").value(6))
			// An unmarked place counts as attended and is also counted on its own.
			.andExpect(jsonPath("$.totals.attended").value(5))
			.andExpect(jsonPath("$.totals.absent").value(1))
			.andExpect(jsonPath("$.totals.unmarked").value(1))
			// The workshop has no limit, so only the three pole classes offer places.
			.andExpect(jsonPath("$.totals.places").value(12))
			.andExpect(jsonPath("$.totals.placesBooked").value(4))
			.andExpect(jsonPath("$.totals.waitlisted").value(1))
			.andExpect(jsonPath("$.classes", hasSize(2)))
			// All occurrences of the series make one class, at the start most of them have.
			.andExpect(jsonPath("$.classes[0].id").value(series.toString()))
			.andExpect(jsonPath("$.classes[0].seriesId").value(series.toString()))
			.andExpect(jsonPath("$.classes[0].title").value("Pole dance"))
			.andExpect(jsonPath("$.classes[0].serviceTypeId").value(pole.toString()))
			.andExpect(jsonPath("$.classes[0].serviceTypeName").value("Pole dance"))
			.andExpect(jsonPath("$.classes[0].calendarColor").value("#E91E63"))
			.andExpect(jsonPath("$.classes[0].trainingGroupId").value(group.toString()))
			.andExpect(jsonPath("$.classes[0].trainingGroupName").value("Pole od podstaw"))
			.andExpect(jsonPath("$.classes[0].weekday").value(2))
			.andExpect(jsonPath("$.classes[0].startTime").value("18:00"))
			.andExpect(jsonPath("$.classes[0].counts.events").value(3))
			.andExpect(jsonPath("$.classes[0].counts.booked").value(4))
			.andExpect(jsonPath("$.classes[0].counts.attended").value(3))
			.andExpect(jsonPath("$.classes[0].counts.places").value(12))
			.andExpect(jsonPath("$.classes[0].counts.placesBooked").value(4))
			.andExpect(jsonPath("$.classes[0].counts.waitlisted").value(1))
			// A one-off workshop is a class of its own.
			.andExpect(jsonPath("$.classes[1].id").value(march.handstand.toString()))
			.andExpect(jsonPath("$.classes[1].seriesId").value(nullValue()))
			.andExpect(jsonPath("$.classes[1].title").value("Warsztaty handstand"))
			.andExpect(jsonPath("$.classes[1].trainingGroupName").value(nullValue()))
			.andExpect(jsonPath("$.classes[1].weekday").value(6))
			.andExpect(jsonPath("$.classes[1].startTime").value("10:00"))
			.andExpect(jsonPath("$.classes[1].counts.booked").value(2))
			.andExpect(jsonPath("$.classes[1].counts.places").value(0))
			.andExpect(jsonPath("$.days", hasSize(4)))
			.andExpect(jsonPath("$.days[0].date").value("2025-03-04"))
			.andExpect(jsonPath("$.days[0].counts.booked").value(3))
			.andExpect(jsonPath("$.days[3].date").value("2025-03-15"));
	}

	@Test
	void countsNoShowsAndCancellationsWithHowLongBeforeTheStartTheyCame() throws Exception {
		new March();

		report("/api/v1/reports/booking-outcomes", "2025-03-01", "2025-03-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.kinds", contains("CLASS", "WORKSHOP")))
			.andExpect(jsonPath("$.totals.events").value(4))
			.andExpect(jsonPath("$.totals.bookings").value(9))
			.andExpect(jsonPath("$.totals.attended").value(5))
			.andExpect(jsonPath("$.totals.absent").value(1))
			.andExpect(jsonPath("$.totals.unmarked").value(1))
			.andExpect(jsonPath("$.totals.cancelledInTime").value(1))
			.andExpect(jsonPath("$.totals.cancelledLate").value(2))
			.andExpect(jsonPath("$.totals.lateEntriesForfeited").value(1))
			// Most bookings first, each with its service's current rules.
			.andExpect(jsonPath("$.services", hasSize(2)))
			.andExpect(jsonPath("$.services[0].serviceTypeId").value(pole.toString()))
			.andExpect(jsonPath("$.services[0].name").value("Pole dance"))
			.andExpect(jsonPath("$.services[0].counts.bookings").value(7))
			.andExpect(jsonPath("$.services[0].counts.cancelledLate").value(2))
			.andExpect(jsonPath("$.services[0].policy.freeCancelHours").value(24))
			.andExpect(jsonPath("$.services[0].policy.lateCancelForfeitsEntry").value(false))
			.andExpect(jsonPath("$.services[1].serviceTypeId").value(workshop.toString()))
			.andExpect(jsonPath("$.services[1].counts.bookings").value(2))
			.andExpect(jsonPath("$.services[1].policy.freeCancelHours").value(12))
			.andExpect(jsonPath("$.services[1].policy.lateCancelForfeitsEntry").value(true))
			.andExpect(jsonPath("$.cancellationLeadTimes", hasSize(6)))
			.andExpect(jsonPath("$.cancellationLeadTimes[0].fromHours").value(0))
			.andExpect(jsonPath("$.cancellationLeadTimes[0].toHours").value(2))
			.andExpect(jsonPath("$.cancellationLeadTimes[0].late").value(1))
			.andExpect(jsonPath("$.cancellationLeadTimes[1].late").value(0))
			.andExpect(jsonPath("$.cancellationLeadTimes[2].fromHours").value(6))
			.andExpect(jsonPath("$.cancellationLeadTimes[2].late").value(1))
			.andExpect(jsonPath("$.cancellationLeadTimes[4].fromHours").value(24))
			.andExpect(jsonPath("$.cancellationLeadTimes[4].inTime").value(1))
			.andExpect(jsonPath("$.cancellationLeadTimes[4].late").value(0))
			.andExpect(jsonPath("$.cancellationLeadTimes[5].fromHours").value(48))
			.andExpect(jsonPath("$.cancellationLeadTimes[5].toHours").value(nullValue()))
			.andExpect(jsonPath("$.days", hasSize(4)))
			.andExpect(jsonPath("$.days[1].date").value("2025-03-11"))
			.andExpect(jsonPath("$.days[1].counts.bookings").value(3))
			.andExpect(jsonPath("$.days[1].counts.cancelledLate").value(2));
	}

	@Test
	void reportsPersonalTrainingOnlyWhenAskedFor() throws Exception {
		new March();

		mvc.perform(get("/api/v1/reports/booking-outcomes").param("from", "2025-03-01")
			.param("to", "2025-03-31")
			.param("kind", "PERSONAL_TRAINING")
			.header("Authorization", "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kinds", contains("PERSONAL_TRAINING")))
			.andExpect(jsonPath("$.totals.events").value(1))
			.andExpect(jsonPath("$.totals.bookings").value(1))
			.andExpect(jsonPath("$.totals.absent").value(1))
			.andExpect(jsonPath("$.totals.attended").value(0))
			.andExpect(jsonPath("$.services", hasSize(1)))
			.andExpect(jsonPath("$.services[0].serviceTypeId").value(personal.toString()));
	}

	@Test
	void leavesOutEventsThatHaveNotStartedYet() throws Exception {
		Instant now = Instant.now();
		LocalDate today = LocalDate.ofInstant(now, ZoneId.of("Europe/Warsaw"));
		String from = today.minusDays(1).toString();
		String to = today.plusDays(7).toString();
		// Other tests may leave events around today, so only the change counts.
		int events = totals("/api/v1/reports/class-attendance", from, to, "events");
		int bookings = totals("/api/v1/reports/booking-outcomes", from, to, "bookings");
		UUID started = insertEvent(pole, "CLASS", null, null, now.minus(Duration.ofHours(1)), 4, "SCHEDULED");
		UUID upcoming = insertEvent(pole, "CLASS", null, null, now.plus(Duration.ofDays(1)), 4, "SCHEDULED");
		reserve(started, profile(), "PRESENT");
		reserve(upcoming, profile(), "UNMARKED");

		assertThat(totals("/api/v1/reports/class-attendance", from, to, "events")).isEqualTo(events + 1);
		assertThat(totals("/api/v1/reports/booking-outcomes", from, to, "bookings")).isEqualTo(bookings + 1);
	}

	@Test
	void filtersTheEventsByServiceGroupStaffRoomAndKind() throws Exception {
		March march = new March();

		filtered("/api/v1/reports/class-attendance", "serviceTypeId", workshop.toString())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.events").value(1))
			.andExpect(jsonPath("$.classes[0].id").value(march.handstand.toString()));
		filtered("/api/v1/reports/class-attendance", "trainingGroupId", group.toString())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.events").value(3));
		// Ewa runs only the workshop.
		filtered("/api/v1/reports/booking-outcomes", "staffId", trainer.toString())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.events").value(1))
			.andExpect(jsonPath("$.totals.bookings").value(2));
		// Only the second pole class took place in the room.
		filtered("/api/v1/reports/booking-outcomes", "resourceId", room.toString())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.events").value(1))
			.andExpect(jsonPath("$.totals.cancelledLate").value(2))
			.andExpect(jsonPath("$.days[0].date").value("2025-03-11"));
		filtered("/api/v1/reports/class-attendance", "kind", "CLASS")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kinds", contains("CLASS")))
			.andExpect(jsonPath("$.totals.events").value(3));
		// A filter takes several values, repeated or separated by commas, and filters combine.
		mvc.perform(get("/api/v1/reports/booking-outcomes").param("from", "2025-03-01")
			.param("to", "2025-03-31")
			.param("kind", "CLASS", "PERSONAL_TRAINING")
			.param("serviceTypeId", pole + "," + personal)
			.param("resourceId", room.toString())
			.header("Authorization", "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kinds", contains("CLASS", "PERSONAL_TRAINING")))
			.andExpect(jsonPath("$.totals.events").value(1));
	}

	@Test
	void hasNoOccupancyForPersonalTraining() throws Exception {
		filtered("/api/v1/reports/class-attendance", "kind", "PERSONAL_TRAINING")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("kind"));
	}

	@Test
	void rejectsAMissingDateOrAMalformedFilter() throws Exception {
		mvc.perform(get("/api/v1/reports/class-attendance").param("to", "2025-03-31")
			.header("Authorization", "Bearer " + ADMIN_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("from"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		filtered("/api/v1/reports/booking-outcomes", "staffId", "not-an-id")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("staffId"));
	}

	@Test
	void reportsNothingForAPeriodWithoutClasses() throws Exception {
		report("/api/v1/reports/class-attendance", "2025-03-01", "2025-03-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.events").value(0))
			.andExpect(jsonPath("$.classes", hasSize(0)))
			.andExpect(jsonPath("$.days", hasSize(0)));
		report("/api/v1/reports/booking-outcomes", "2025-03-01", "2025-03-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.bookings").value(0))
			.andExpect(jsonPath("$.services", hasSize(0)))
			.andExpect(jsonPath("$.cancellationLeadTimes", hasSize(6)));
	}

	@Test
	void rejectsAReversedOrTooLongPeriod() throws Exception {
		for (String path : List.of("/api/v1/reports/class-attendance", "/api/v1/reports/booking-outcomes")) {
			report(path, "2025-03-31", "2025-03-01").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("to"));
			report(path, "2023-01-01", "2025-01-01").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("to"));
		}
		mvc.perform(get("/api/v1/reports/booking-outcomes").param("from", "2025-03-01")
			.param("to", "2025-03-31")
			.param("kind", "EVERYTHING")
			.header("Authorization", "Bearer " + ADMIN_TOKEN)).andExpect(status().isBadRequest());
	}

	@Test
	void isOnlyForAdministrators() throws Exception {
		for (String path : List.of("/api/v1/reports/class-attendance", "/api/v1/reports/booking-outcomes")) {
			mvc.perform(get(path).param("from", "2025-03-01")
				.param("to", "2025-03-31")
				.header("Authorization", "Bearer " + TRAINER_TOKEN)).andExpect(status().isForbidden());
		}
	}

	/**
	 * March 2025: a weekly pole dance class on Tuesdays at 18:00 (one occurrence moved to Thursday
	 * 19:00) with four places, a one-off workshop without a limit, a personal session and events
	 * that do not count.
	 */
	private final class March {

		final UUID handstand;

		March() {
			UUID anna = profile();
			UUID beata = profile();
			UUID celina = profile();
			UUID dorota = profile();
			UUID ewa = profile();

			UUID first = insertEvent(pole, "CLASS", group, series, at("2025-03-04T17:00:00Z"), 4, "COMPLETED");
			reserve(first, anna, "PRESENT");
			reserve(first, beata, "ABSENT");
			reserve(first, celina, "UNMARKED");
			// 31 hours before the start: in time.
			cancel(first, dorota, "IN_TIME", at("2025-03-03T10:00:00Z"), true);
			jdbc.sql("""
					INSERT INTO waitlist_entry (event_id, customer_id, added_by, status)
					VALUES (:event, :customer, :addedBy, 'CANCELLED')
					""").param("event", first).param("customer", ewa).param("addedBy", adminAccount).update();

			UUID second = insertEvent(pole, "CLASS", group, series, at("2025-03-11T17:00:00Z"), 4, "COMPLETED");
			reserve(second, anna, "PRESENT");
			// An hour before the start, the entry forfeited; seven hours before, returned.
			cancel(second, beata, "LATE", at("2025-03-11T16:00:00Z"), false);
			cancel(second, celina, "LATE", at("2025-03-11T10:00:00Z"), true);
			jdbc.sql("INSERT INTO event_resource (event_id, resource_id) VALUES (:event, :room)")
				.param("event", second)
				.param("room", room)
				.update();

			insertEvent(pole, "CLASS", group, series, at("2025-03-13T18:00:00Z"), 4, "SCHEDULED");

			this.handstand = insertEvent(workshop, "WORKSHOP", null, null, at("2025-03-15T09:00:00Z"), null,
					"COMPLETED");
			jdbc.sql("UPDATE schedule_event SET title = 'Warsztaty handstand' WHERE id = :id")
				.param("id", this.handstand)
				.update();
			reserve(this.handstand, anna, "PRESENT");
			reserve(this.handstand, beata, "PRESENT");
			jdbc.sql("INSERT INTO event_staff (event_id, staff_id) VALUES (:event, :staff)")
				.param("event", this.handstand)
				.param("staff", trainer)
				.update();

			UUID session = insertEvent(personal, "PERSONAL_TRAINING", null, null, at("2025-03-05T09:00:00Z"), 1,
					"COMPLETED");
			reserve(session, anna, "ABSENT");

			UUID cancelled = insertEvent(pole, "CLASS", group, series, at("2025-03-18T17:00:00Z"), 4, "CANCELLED");
			reserve(cancelled, anna, "UNMARKED");
			UUID april = insertEvent(pole, "CLASS", group, series, at("2025-04-01T16:00:00Z"), 4, "COMPLETED");
			reserve(april, anna, "PRESENT");
		}

	}

	private ResultActions report(String path, String from, String to) throws Exception {
		return mvc.perform(get(path).param("from", from).param("to", to).header("Authorization", "Bearer " + ADMIN_TOKEN));
	}

	/** The report for March 2025 with one filter. */
	private ResultActions filtered(String path, String filter, String value) throws Exception {
		return mvc.perform(get(path).param("from", "2025-03-01")
			.param("to", "2025-03-31")
			.param(filter, value)
			.header("Authorization", "Bearer " + ADMIN_TOKEN));
	}

	private int totals(String path, String from, String to, String count) throws Exception {
		String body = report(path, from, to).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.totals." + count);
	}

	private static Instant at(String instant) {
		return Instant.parse(instant);
	}

	private UUID insertService(String name, String category, String color) {
		UUID id = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, calendar_color)
				VALUES (:name, :category, 60, :color)
				RETURNING id
				""").param("name", name).param("category", category).param("color", color).query(UUID.class).single();
		services.add(id);
		return id;
	}

	private UUID insertEvent(UUID serviceType, String kind, @Nullable UUID trainingGroup, @Nullable UUID recurrence,
			Instant startsAt, @Nullable Integer capacity, String status) {
		OffsetDateTime start = OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC);
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, training_group_id, series_id, starts_at, ends_at,
				                            capacity, status)
				VALUES (:kind, :service, :group, :series, :startsAt, :endsAt, :capacity, :status)
				RETURNING id
				""")
			.param("kind", kind)
			.param("service", serviceType)
			.param("group", trainingGroup)
			.param("series", recurrence)
			.param("startsAt", start)
			.param("endsAt", start.plusHours(1))
			.param("capacity", capacity)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

	private UUID profile() {
		UUID id = jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name) VALUES ('ADULT', 'Jan', 'Testowy')
				RETURNING id
				""").query(UUID.class).single();
		profiles.add(id);
		return id;
	}

	private void reserve(UUID event, UUID customer, String attendance) {
		jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status, attendance)
				VALUES (:event, :customer, :bookedBy, 'ACTIVE', :attendance)
				""")
			.param("event", event)
			.param("customer", customer)
			.param("bookedBy", adminAccount)
			.param("attendance", attendance)
			.update();
	}

	private void cancel(UUID event, UUID customer, String kind, Instant cancelledAt, boolean entryReturned) {
		jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status, cancelled_at, cancel_kind,
				                         entry_returned)
				VALUES (:event, :customer, :bookedBy, 'CANCELLED', :cancelledAt, :kind, :returned)
				""")
			.param("event", event)
			.param("customer", customer)
			.param("bookedBy", adminAccount)
			.param("cancelledAt", OffsetDateTime.ofInstant(cancelledAt, ZoneOffset.UTC))
			.param("kind", kind)
			.param("returned", entryReturned)
			.update();
	}

	private UUID addAccount(String email, String roleCode) {
		UUID id = jdbc.sql("""
				INSERT INTO account (email, status, activated_at) VALUES (:email, 'ACTIVE', now()) RETURNING id
				""").param("email", email).query(UUID.class).single();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id)
			.param("code", roleCode)
			.update();
		return id;
	}

	private void signIn(UUID account, String firebaseUid, String token) {
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", account)
			.param("uid", firebaseUid)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
	}

}
