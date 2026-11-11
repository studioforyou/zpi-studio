package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Trainers record attendance and mark their events as held (#102); administrators correct it with
 * an optional reason, keeping its history (#111). Firebase ID tokens are mocked at the
 * {@link JwtDecoder}. The tests are not transactional, so requests run as in production; the data
 * is removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventAttendanceTests {

	private static final String OWN = "/api/v1/me/schedule-events/";

	private static final String ADMIN = "/api/v1/schedule-events/";

	private static final String LEAD_TOKEN = "attendance-lead-token";

	private static final String ASSISTANT_TOKEN = "attendance-assistant-token";

	private static final String OTHER_TRAINER_TOKEN = "attendance-other-trainer-token";

	private static final String ADMIN_TOKEN = "attendance-admin-token";

	private static final String CLIENT_TOKEN = "attendance-client-token";

	private static final String EMAIL_DOMAIN = "@attendance.test";

	private static final String EVENT_TITLE = "attendance-test-event";

	private static final String LAST_NAME = "attendance-";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID clientAccount;

	private UUID leadAccount;

	private UUID adminAccount;

	private UUID lead;

	private UUID assistant;

	private UUID product;

	private UUID variant;

	@BeforeEach
	void setUp() {
		leadAccount = addAccount("lead", "TRAINER", LEAD_TOKEN);
		lead = insertStaff(leadAccount, "Anna", "Kowalska");
		assistant = insertStaff(addAccount("assistant", "TRAINER", ASSISTANT_TOKEN), "Marek", "Nowak");
		insertStaff(addAccount("other", "TRAINER", OTHER_TRAINER_TOKEN), "Ewa", "Lis");
		adminAccount = addAccount("admin", "ADMIN", ADMIN_TOKEN);
		insertStaff(adminAccount, "Beata", "Wrona");
		clientAccount = addAccount("client", "CLIENT", CLIENT_TOKEN);
		product = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES ('attendance-pass', 'ENTRY_PASS') RETURNING id")
			.query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 5, 30, 200) RETURNING id
				""").param("product", product).query(UUID.class).single();
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'";
		PassNotificationCleanup.deleteNotifications(jdbc, accounts);
		String events = "SELECT id FROM schedule_event WHERE title = '" + EVENT_TITLE + "'";
		String reservations = "SELECT id FROM reservation WHERE event_id IN (" + events + ")";
		String passes = "SELECT id FROM customer_pass WHERE variant_id = '" + variant + "'";
		jdbc.sql("DELETE FROM audit_log WHERE entity_id IN (" + reservations + ") OR entity_id IN (" + events + ")")
			.update();
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (" + passes + ")").update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM customer_pass WHERE variant_id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM event_staff WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM schedule_event WHERE title = '" + EVENT_TITLE + "'").update();
		jdbc.sql("DELETE FROM customer_profile WHERE last_name LIKE '" + LAST_NAME + "%'").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'").update();
	}

	@Test
	void theTrainersRecordAttendanceAndTheFirstMarkUsesThePassEntryOnce() throws Exception {
		UUID event = event("CLASS", Instant.now().minus(30, ChronoUnit.MINUTES), Duration.ofHours(1));
		assign(event, lead, "LEAD");
		assign(event, assistant, "ASSISTANT");
		UUID ola = customer("Ola", LAST_NAME + "Nowak");
		UUID piotr = customer("Piotr", LAST_NAME + "Żak");
		UUID olaPass = pass(ola, 4);
		UUID olaReservation = reserve(event, ola, "ACTIVE", olaPass);
		UUID piotrReservation = reserve(event, piotr, "ACTIVE", null);
		reserve(event, customer("Jan", LAST_NAME + "Adamski"), "CANCELLED", null);

		perform(get(OWN + event + "/attendance"), ASSISTANT_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.eventStatus").value("SCHEDULED"))
			.andExpect(jsonPath("$.markingOpen").value(true))
			.andExpect(jsonPath("$.participants[*].lastName").value(contains(LAST_NAME + "Nowak", LAST_NAME + "Żak")))
			.andExpect(jsonPath("$.participants[0].attendance").value("UNMARKED"))
			.andExpect(jsonPath("$.participants[0].attendanceMarkedAt").value(nullValue()))
			.andExpect(jsonPath("$.participants[0].phone").doesNotExist());

		mark(LEAD_TOKEN, event, Map.of(olaReservation, "ABSENT", piotrReservation, "PRESENT"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.participants[0].attendance").value("ABSENT"))
			.andExpect(jsonPath("$.participants[0].attendanceMarkedAt").isNotEmpty())
			.andExpect(jsonPath("$.participants[1].attendance").value("PRESENT"));
		// The absence used the entry; changing it to present uses nothing more.
		assertThat(balance(olaPass)).isEqualTo(4);
		assertThat(ledgerKinds(olaPass)).containsExactly("ENTRY_RESERVED", "ENTRY_CONSUMED");
		mark(ASSISTANT_TOKEN, event, Map.of(olaReservation, "PRESENT")).andExpect(status().isOk())
			.andExpect(jsonPath("$.participants[0].attendance").value("PRESENT"));
		mark(ASSISTANT_TOKEN, event, Map.of(olaReservation, "PRESENT")).andExpect(status().isOk());
		assertThat(balance(olaPass)).isEqualTo(4);
		assertThat(ledgerKinds(olaPass)).containsExactly("ENTRY_RESERVED", "ENTRY_CONSUMED");
		assertThat(attendanceActions(olaReservation)).containsExactly("ATTENDANCE_MARKED", "ATTENDANCE_MARKED");
		assertThat(eventStatus(event)).isEqualTo("SCHEDULED");
	}

	@Test
	void markingTheEventAsHeldRecordsTheUnmarkedAsPresentAndCompletesIt() throws Exception {
		UUID event = event("CLASS", Instant.now().minus(3, ChronoUnit.HOURS), Duration.ofHours(1));
		assign(event, lead, "LEAD");
		UUID ola = customer("Ola", LAST_NAME + "Nowak");
		UUID olaPass = pass(ola, 2);
		UUID olaReservation = reserve(event, ola, "ACTIVE", olaPass);
		UUID absent = reserve(event, customer("Piotr", LAST_NAME + "Żak"), "ACTIVE", null);
		mark(LEAD_TOKEN, event, Map.of(absent, "ABSENT")).andExpect(status().isOk());

		perform(post(OWN + event + "/complete"), LEAD_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.eventStatus").value("COMPLETED"))
			.andExpect(jsonPath("$.markingOpen").value(true))
			.andExpect(jsonPath("$.participants[0].attendance").value("PRESENT"))
			.andExpect(jsonPath("$.participants[1].attendance").value("ABSENT"));
		assertThat(eventStatus(event)).isEqualTo("COMPLETED");
		assertThat(ledgerKinds(olaPass)).containsExactly("ENTRY_RESERVED", "ENTRY_CONSUMED");
		assertThat(attendanceActions(olaReservation)).containsExactly("ATTENDANCE_DEFAULTED");
		assertThat(jdbc.sql("SELECT action FROM audit_log WHERE entity_id = :event").param("event", event)
			.query(String.class).list()).containsExactly("EVENT_COMPLETED");
		assertThat(trainerConfirmed(olaReservation)).isFalse();

		// Repeating it changes nothing; the trainer can still change attendance in their time.
		perform(post(OWN + event + "/complete"), LEAD_TOKEN).andExpect(status().isOk());
		mark(LEAD_TOKEN, event, Map.of(absent, "PRESENT")).andExpect(status().isOk());
		assertThat(attendanceActions(olaReservation)).containsExactly("ATTENDANCE_DEFAULTED");
		assertThat(ledgerKinds(olaPass)).containsExactly("ENTRY_RESERVED", "ENTRY_CONSUMED");
	}

	@Test
	void aHeldPersonalSessionGetsTheTrainersConfirmation() throws Exception {
		UUID event = event("PERSONAL_TRAINING", Instant.now().minus(2, ChronoUnit.HOURS), Duration.ofHours(1));
		assign(event, lead, "LEAD");
		UUID reservation = reserve(event, customer("Tomasz", LAST_NAME + "Wiśniewski"), "ACTIVE", null);

		perform(post(OWN + event + "/complete"), LEAD_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.eventKind").value("PERSONAL_TRAINING"))
			.andExpect(jsonPath("$.participants[0].attendance").value("PRESENT"));
		assertThat(trainerConfirmed(reservation)).isTrue();
	}

	@Test
	void theTrainersTimeRunsFromTheStartUntilTwelveHoursAfterTheEnd() throws Exception {
		UUID future = event("CLASS", Instant.now().plus(1, ChronoUnit.HOURS), Duration.ofHours(1));
		UUID old = event("CLASS", Instant.now().minus(14, ChronoUnit.HOURS), Duration.ofHours(1));
		UUID cancelled = event("CLASS", Instant.now().minus(1, ChronoUnit.HOURS), Duration.ofHours(1));
		jdbc.sql("UPDATE schedule_event SET status = 'CANCELLED' WHERE id = :id").param("id", cancelled).update();
		for (UUID event : List.of(future, old, cancelled)) {
			assign(event, lead, "LEAD");
		}
		UUID ola = customer("Ola", LAST_NAME + "Nowak");
		Map<UUID, UUID> reservations = Map.of(future, reserve(future, ola, "ACTIVE", null), old,
				reserve(old, ola, "ACTIVE", null), cancelled, reserve(cancelled, ola, "ACTIVE", null));

		perform(get(OWN + future + "/attendance"), LEAD_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.markingOpen").value(false));
		perform(get(OWN + old + "/attendance"), LEAD_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.markingOpen").value(false));
		Map<UUID, String> expected = Map.of(future, "ATTENDANCE_NOT_OPEN", old, "ATTENDANCE_CLOSED", cancelled,
				"EVENT_CANCELLED");
		for (Map.Entry<UUID, String> rejected : expected.entrySet()) {
			UUID event = rejected.getKey();
			mark(LEAD_TOKEN, event, Map.of(reservations.get(event), "PRESENT"))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value(rejected.getValue()));
			perform(post(OWN + event + "/complete"), LEAD_TOKEN).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value(rejected.getValue()));
		}
		assertThat(eventStatus(old)).isEqualTo("SCHEDULED");
	}

	@Test
	void marksAreCheckedAllTogether() throws Exception {
		UUID event = event("CLASS", Instant.now().minus(30, ChronoUnit.MINUTES), Duration.ofHours(1));
		assign(event, lead, "LEAD");
		UUID active = reserve(event, customer("Ola", LAST_NAME + "Nowak"), "ACTIVE", null);
		UUID cancelled = reserve(event, customer("Jan", LAST_NAME + "Adamski"), "CANCELLED", null);
		UUID otherEvent = event("CLASS", Instant.now().minus(30, ChronoUnit.MINUTES), Duration.ofHours(1));
		UUID elsewhere = reserve(otherEvent, customer("Piotr", LAST_NAME + "Żak"), "ACTIVE", null);

		mark(LEAD_TOKEN, event, Map.of(active, "PRESENT", cancelled, "PRESENT"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));
		mark(LEAD_TOKEN, event, Map.of(active, "PRESENT", elsewhere, "PRESENT")).andExpect(status().isNotFound());
		assertThat(attendance(active)).isEqualTo("UNMARKED");
		perform(patch(OWN + event + "/attendance").contentType(MediaType.APPLICATION_JSON).content("""
				{"marks": [{"reservationId": "%s", "attendance": "PRESENT"},
				           {"reservationId": "%s", "attendance": "ABSENT"}]}
				""".formatted(active, active)), LEAD_TOKEN)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		perform(patch(OWN + event + "/attendance").contentType(MediaType.APPLICATION_JSON).content("""
				{"marks": [{"reservationId": "%s", "attendance": "UNMARKED"}]}
				""".formatted(active)), LEAD_TOKEN).andExpect(status().isBadRequest());
		perform(patch(OWN + event + "/attendance").contentType(MediaType.APPLICATION_JSON).content("{\"marks\": []}"),
				LEAD_TOKEN).andExpect(status().isBadRequest());
		assertThat(attendance(active)).isEqualTo("UNMARKED");
	}

	@Test
	void onlyTheEventsTrainersRecordItsAttendance() throws Exception {
		UUID event = event("CLASS", Instant.now().minus(30, ChronoUnit.MINUTES), Duration.ofHours(1));
		assign(event, lead, "LEAD");
		UUID reservation = reserve(event, customer("Ola", LAST_NAME + "Nowak"), "ACTIVE", null);

		perform(get(OWN + event + "/attendance"), OTHER_TRAINER_TOKEN).andExpect(status().isNotFound());
		mark(OTHER_TRAINER_TOKEN, event, Map.of(reservation, "PRESENT")).andExpect(status().isNotFound());
		perform(post(OWN + event + "/complete"), OTHER_TRAINER_TOKEN).andExpect(status().isNotFound());
		for (String token : List.of(ADMIN_TOKEN, CLIENT_TOKEN)) {
			perform(get(OWN + event + "/attendance"), token).andExpect(status().isForbidden());
			mark(token, event, Map.of(reservation, "PRESENT")).andExpect(status().isForbidden());
		}
		for (String token : List.of(LEAD_TOKEN, CLIENT_TOKEN)) {
			perform(get(ADMIN + event + "/attendance"), token).andExpect(status().isForbidden());
			correct(token, event, reservation, "PRESENT", null, 0).andExpect(status().isForbidden());
		}
		assertThat(attendance(reservation)).isEqualTo("UNMARKED");
		assertThat(eventStatus(event)).isEqualTo("SCHEDULED");
	}

	@Test
	void anAdministratorCorrectsAttendanceWithAReasonKeepingItsHistory() throws Exception {
		// Long after the trainer's time, which does not limit administrators.
		UUID event = event("CLASS", Instant.now().minus(3, ChronoUnit.DAYS), Duration.ofHours(1));
		assign(event, lead, "LEAD");
		UUID ola = customer("Ola", LAST_NAME + "Nowak");
		UUID olaPass = pass(ola, 3);
		UUID reservation = reserve(event, ola, "ACTIVE", olaPass);
		markedBy(reservation, "PRESENT", leadAccount);

		perform(get(ADMIN + event + "/attendance"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.correctable").value(true))
			.andExpect(jsonPath("$.trainerMarkingOpen").value(false))
			.andExpect(jsonPath("$.participants[0].attendance").value("PRESENT"))
			.andExpect(jsonPath("$.participants[0].attendanceMarkedBy").value("Anna Kowalska"))
			.andExpect(jsonPath("$.participants[0].version").value(0))
			.andExpect(jsonPath("$.participants[0].history.length()").value(0));

		correct(ADMIN_TOKEN, event, reservation, "ABSENT", "  The participant did not come  ", 0)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attendance").value("ABSENT"))
			.andExpect(jsonPath("$.attendanceMarkedBy").value("Beata Wrona"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.history[0].kind").value("CORRECTED"))
			.andExpect(jsonPath("$.history[0].previousAttendance").value("PRESENT"))
			.andExpect(jsonPath("$.history[0].newAttendance").value("ABSENT"))
			.andExpect(jsonPath("$.history[0].changedBy").value("Beata Wrona"))
			.andExpect(jsonPath("$.history[0].reason").value("The participant did not come"));
		correct(ADMIN_TOKEN, event, reservation, "PRESENT", null, 0).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		correct(ADMIN_TOKEN, event, reservation, "PRESENT", " ", 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.history.length()").value(2))
			.andExpect(jsonPath("$.history[1].previousAttendance").value("ABSENT"))
			.andExpect(jsonPath("$.history[1].reason").value(nullValue()));
		correct(ADMIN_TOKEN, event, reservation, "PRESENT", "x".repeat(501), 2).andExpect(status().isBadRequest());

		perform(get(ADMIN + event + "/attendance"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.participants[0].history[*].newAttendance").value(contains("ABSENT", "PRESENT")));
		// A correction between present and absent never changes the pass.
		assertThat(balance(olaPass)).isEqualTo(3);
		assertThat(ledgerKinds(olaPass)).containsExactly("ENTRY_RESERVED", "ENTRY_CONSUMED");
	}

	@Test
	void anAdministratorRecordsMissingAttendanceAndTheTrainersMarksAreInTheHistory() throws Exception {
		UUID event = event("CLASS", Instant.now().minus(30, ChronoUnit.MINUTES), Duration.ofHours(1));
		assign(event, lead, "LEAD");
		UUID ola = customer("Ola", LAST_NAME + "Nowak");
		UUID olaPass = pass(ola, 3);
		UUID unmarked = reserve(event, ola, "ACTIVE", olaPass);
		UUID marked = reserve(event, customer("Piotr", LAST_NAME + "Żak"), "ACTIVE", null);
		mark(LEAD_TOKEN, event, Map.of(marked, "ABSENT")).andExpect(status().isOk());

		correct(ADMIN_TOKEN, event, unmarked, "PRESENT", null, 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.history[0].previousAttendance").value("UNMARKED"));
		assertThat(ledgerKinds(olaPass)).containsExactly("ENTRY_RESERVED", "ENTRY_CONSUMED");
		perform(get(ADMIN + event + "/attendance"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.participants[1].history[0].kind").value("MARKED"))
			.andExpect(jsonPath("$.participants[1].history[0].changedBy").value("Anna Kowalska"));
	}

	@Test
	void attendanceIsCorrectedOnlyForActiveReservationsOfEventsThatStarted() throws Exception {
		UUID future = event("CLASS", Instant.now().plus(1, ChronoUnit.DAYS), Duration.ofHours(1));
		UUID cancelledEvent = event("CLASS", Instant.now().minus(1, ChronoUnit.DAYS), Duration.ofHours(1));
		jdbc.sql("UPDATE schedule_event SET status = 'CANCELLED' WHERE id = :id").param("id", cancelledEvent).update();
		UUID past = event("CLASS", Instant.now().minus(1, ChronoUnit.DAYS), Duration.ofHours(1));
		UUID ola = customer("Ola", LAST_NAME + "Nowak");
		UUID futureReservation = reserve(future, ola, "ACTIVE", null);
		UUID cancelledEventReservation = reserve(cancelledEvent, ola, "ACTIVE", null);
		UUID cancelledReservation = reserve(past, ola, "CANCELLED", null);

		perform(get(ADMIN + future + "/attendance"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.correctable").value(false));
		correct(ADMIN_TOKEN, future, futureReservation, "PRESENT", null, 0)
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_OPEN"));
		correct(ADMIN_TOKEN, cancelledEvent, cancelledEventReservation, "PRESENT", null, 0)
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVENT_CANCELLED"));
		correct(ADMIN_TOKEN, past, cancelledReservation, "PRESENT", null, 0)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESERVATION_NOT_ACTIVE"));
		correct(ADMIN_TOKEN, past, futureReservation, "PRESENT", null, 0).andExpect(status().isNotFound());
		perform(get(ADMIN + UUID.randomUUID() + "/attendance"), ADMIN_TOKEN).andExpect(status().isNotFound());
	}

	private ResultActions mark(String token, UUID eventId, Map<UUID, String> marks) throws Exception {
		String items = String.join(",", marks.entrySet().stream()
			.map(mark -> "{\"reservationId\": \"%s\", \"attendance\": \"%s\"}".formatted(mark.getKey(), mark.getValue()))
			.toList());
		return perform(patch(OWN + eventId + "/attendance").contentType(MediaType.APPLICATION_JSON)
			.content("{\"marks\": [" + items + "]}"), token);
	}

	private ResultActions correct(String token, UUID eventId, UUID reservationId, String attendance, String reason,
			int version) throws Exception {
		String body = "{\"attendance\": \"%s\", \"reason\": %s, \"version\": %d}".formatted(attendance,
				reason == null ? "null" : "\"" + reason + "\"", version);
		return perform(post(ADMIN + eventId + "/reservations/" + reservationId + "/correct-attendance")
			.contentType(MediaType.APPLICATION_JSON).content(body), token);
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String token) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + token));
	}

	/** Adds an active account with the role, signed in with the token. */
	private UUID addAccount(String name, String roleCode, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + EMAIL_DOMAIN).query(UUID.class).single();
		String uid = "attendance-" + id;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id).param("code", roleCode).update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
		return id;
	}

	private UUID insertStaff(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name)
				VALUES (:accountId, :firstName, :lastName) RETURNING id
				""").param("accountId", accountId).param("firstName", firstName).param("lastName", lastName)
			.query(UUID.class).single();
	}

	private UUID customer(String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name) VALUES ('ADULT', :firstName, :lastName)
				RETURNING id
				""").param("firstName", firstName).param("lastName", lastName).query(UUID.class).single();
	}

	private UUID event(String kind, Instant startsAt, Duration length) {
		Instant start = startsAt.truncatedTo(ChronoUnit.SECONDS);
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, title, starts_at, ends_at, capacity, visibility)
				VALUES (:kind, :title, :startsAt, :endsAt, 12, 'PUBLIC') RETURNING id
				""").param("kind", kind).param("title", EVENT_TITLE).param("startsAt", start.atOffset(ZoneOffset.UTC))
			.param("endsAt", start.plus(length).atOffset(ZoneOffset.UTC)).query(UUID.class).single();
	}

	private void assign(UUID eventId, UUID staffId, String role) {
		jdbc.sql("INSERT INTO event_staff (event_id, staff_id, role) VALUES (:event, :staff, :role)")
			.param("event", eventId).param("staff", staffId).param("role", role).update();
	}

	/** A finite pass with the given balance left after its entry for the reservation was allocated. */
	private UUID pass(UUID customerId, int remaining) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				    price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'attendance-pass', 5, :remaining, 200, 'ACTIVE', :from, :until) RETURNING id
				""").param("customer", customerId).param("variant", variant).param("remaining", remaining)
			.param("from", LocalDate.now().minusDays(10)).param("until", LocalDate.now().plusDays(10))
			.query(UUID.class).single();
	}

	private UUID reserve(UUID eventId, UUID customerId, String status, UUID passId) {
		UUID reservation = jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status, customer_pass_id)
				VALUES (:event, :customer, :bookedBy, :status, :pass) RETURNING id
				""").param("event", eventId).param("customer", customerId).param("bookedBy", clientAccount)
			.param("status", status).param("pass", passId).query(UUID.class).single();
		if (passId != null) {
			jdbc.sql("""
					INSERT INTO pass_ledger (pass_id, kind, entries_delta, reservation_id, actor_account_id)
					VALUES (:pass, 'ENTRY_RESERVED', -1, :reservation, :actor)
					""").param("pass", passId).param("reservation", reservation).param("actor", clientAccount).update();
		}
		return reservation;
	}

	/** Attendance recorded before, without its history. */
	private void markedBy(UUID reservation, String attendance, UUID accountId) {
		jdbc.sql("""
				UPDATE reservation SET attendance = :attendance, attendance_marked_by = :account,
				attendance_marked_at = now() WHERE id = :id
				""").param("attendance", attendance).param("account", accountId).param("id", reservation).update();
	}

	private String attendance(UUID reservation) {
		return jdbc.sql("SELECT attendance FROM reservation WHERE id = :id").param("id", reservation)
			.query(String.class).single();
	}

	private boolean trainerConfirmed(UUID reservation) {
		return jdbc.sql("SELECT trainer_confirmed_at IS NOT NULL FROM reservation WHERE id = :id")
			.param("id", reservation).query(Boolean.class).single();
	}

	private String eventStatus(UUID event) {
		return jdbc.sql("SELECT status FROM schedule_event WHERE id = :id").param("id", event)
			.query(String.class).single();
	}

	private int balance(UUID pass) {
		return jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :pass").param("pass", pass)
			.query(Integer.class).single();
	}

	private List<String> ledgerKinds(UUID pass) {
		return jdbc.sql("SELECT kind FROM pass_ledger WHERE pass_id = :pass ORDER BY occurred_at, kind DESC")
			.param("pass", pass).query(String.class).list();
	}

	private List<String> attendanceActions(UUID reservation) {
		return jdbc.sql("SELECT action FROM audit_log WHERE entity_id = :id ORDER BY occurred_at, id")
			.param("id", reservation).query(String.class).list();
	}

}
