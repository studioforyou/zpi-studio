package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.Notifications;

/**
 * Trainers request time off and administrators decide on it. The JWT decoder and the
 * notifications are mocked; authorization, validation and PostgreSQL writes run for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StaffTimeOffTests {

	private static final String OWN = "/api/v1/me/time-off";

	private static final String ADMIN_PATH = "/api/v1/staff/time-off";

	private static final String TRAINER = "time-off-trainer-token";

	private static final String OTHER_TRAINER = "time-off-other-token";

	private static final String CLIENT = "time-off-client-token";

	private static final String ADMIN = "time-off-admin-token";

	private static final String NO_PROFILE = "time-off-no-profile-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private Notifications notifications;

	private UUID trainerAccountId;

	private UUID trainerStaffId;

	private UUID otherStaffId;

	private UUID adminAccountId;

	private Instant base;

	@BeforeEach
	void setUp() {
		trainerAccountId = addAccount("trainer", TRAINER, "TRAINER");
		trainerStaffId = addStaffProfile(trainerAccountId, "Anna", "Kowalska");
		UUID otherAccountId = addAccount("other", OTHER_TRAINER, "TRAINER");
		otherStaffId = addStaffProfile(otherAccountId, "Piotr", "Nowak");
		addAccount("client", CLIENT, "CLIENT");
		adminAccountId = addAccount("admin", ADMIN, "ADMIN");
		addStaffProfile(adminAccountId, "Beata", "Lis");
		addAccount("missing", NO_PROFILE, "TRAINER");
		base = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE 'time-off-%@example.com'";
		jdbc.sql("DELETE FROM staff_time_off WHERE staff_id IN (SELECT id FROM staff_profile WHERE account_id IN ("
				+ accounts + "))").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE 'time-off-%@example.com'").update();
	}

	@Test
	void trainerRequestsTimeOffThatWaitsForADecision() throws Exception {
		perform(TRAINER, post(OWN), request("VACATION", base, base.plus(Duration.ofDays(5)), "  Wyjazd  "))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.containsString(OWN + "/")))
			.andExpect(jsonPath("$.staffId").value(trainerStaffId.toString()))
			.andExpect(jsonPath("$.staffDisplayName").value("Anna Kowalska"))
			.andExpect(jsonPath("$.kind").value("VACATION"))
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.startsAt").value(base.toString()))
			.andExpect(jsonPath("$.reason").value("Wyjazd"))
			.andExpect(jsonPath("$.decidedAt").doesNotExist());

		perform(TRAINER, get(OWN), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("VACATION"));
		perform(OTHER_TRAINER, get(OWN), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void ownListShowsTheLatestFirstWithBlankReasonStoredAsNull() throws Exception {
		requestOwn(TRAINER, "SICK_LEAVE", base, base.plus(Duration.ofDays(1)));
		perform(TRAINER, post(OWN), request("UNAVAILABLE", base.plus(Duration.ofDays(3)),
				base.plus(Duration.ofDays(3)).plus(Duration.ofHours(4)), "   "))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reason").doesNotExist());

		perform(TRAINER, get(OWN), null)
			.andExpect(jsonPath("$.items[0].kind").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.items[1].kind").value("SICK_LEAVE"));
	}

	@Test
	void invalidPeriodsAreRejected() throws Exception {
		perform(TRAINER, post(OWN), request("VACATION", base, base, null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("endsAt"));
		perform(TRAINER, post(OWN), request("VACATION", base, base.plus(Duration.ofDays(367)), null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
		Instant past = Instant.now().minus(Duration.ofDays(3));
		perform(TRAINER, post(OWN), request("SICK_LEAVE", past, past.plus(Duration.ofDays(1)), null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endsAt"));
		perform(TRAINER, post(OWN), "{\"kind\":\"HOLIDAY\",\"startsAt\":\"" + base + "\",\"endsAt\":\""
				+ base.plus(Duration.ofDays(1)) + "\"}")
			.andExpect(status().isBadRequest());
		perform(TRAINER, post(OWN), "{\"startsAt\":\"" + base + "\",\"endsAt\":\"" + base.plus(Duration.ofDays(1))
				+ "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		perform(TRAINER, post(OWN), request("VACATION", base, base.plus(Duration.ofDays(1)), "x".repeat(501)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(countFor(trainerStaffId)).isZero();
	}

	@Test
	void overlappingRequestsOfOneTrainerAreRejected() throws Exception {
		String first = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(5)));

		perform(TRAINER, post(OWN), request("SICK_LEAVE", base.plus(Duration.ofDays(4)),
				base.plus(Duration.ofDays(6)), null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TIME_OFF_OVERLAP"))
			.andExpect(jsonPath("$.conflictingTimeOffId").value(first));
		// Touching periods do not overlap, and other trainers are independent.
		requestOwn(TRAINER, "SICK_LEAVE", base.plus(Duration.ofDays(5)), base.plus(Duration.ofDays(6)));
		requestOwn(OTHER_TRAINER, "VACATION", base, base.plus(Duration.ofDays(5)));
		// Rejected and cancelled time off no longer blocks the period.
		perform(ADMIN, post(ADMIN_PATH + "/" + first + "/reject"), null).andExpect(status().isOk());
		requestOwn(TRAINER, "UNAVAILABLE", base.plus(Duration.ofDays(1)), base.plus(Duration.ofDays(2)));
	}

	@Test
	void adminApprovesOrRejectsRequestsOnce() throws Exception {
		String vacation = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(5)));
		String other = requestOwn(OTHER_TRAINER, "UNAVAILABLE", base, base.plus(Duration.ofHours(3)));

		perform(ADMIN, post(ADMIN_PATH + "/" + vacation + "/approve"), "{\"note\":\" Udanego urlopu \"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.decisionNote").value("Udanego urlopu"))
			.andExpect(jsonPath("$.decidedAt").exists())
			.andExpect(jsonPath("$.decidedByName").value("Beata Lis"))
			.andExpect(jsonPath("$.recordedByStudio").value(false));
		assertThat(jdbc.sql("SELECT decided_by FROM staff_time_off WHERE id = :id")
			.param("id", UUID.fromString(vacation))
			.query(UUID.class)
			.single()).isEqualTo(adminAccountId);
		perform(ADMIN, post(ADMIN_PATH + "/" + other + "/reject"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"))
			.andExpect(jsonPath("$.decisionNote").doesNotExist());

		perform(ADMIN, post(ADMIN_PATH + "/" + vacation + "/reject"), null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TIME_OFF_ALREADY_DECIDED"))
			.andExpect(jsonPath("$.status").value("APPROVED"));
		perform(ADMIN, post(ADMIN_PATH + "/" + UUID.randomUUID() + "/approve"), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(TRAINER, get(OWN), null).andExpect(jsonPath("$.items[0].status").value("APPROVED"));
	}

	@Test
	void adminListsAndFiltersTimeOff() throws Exception {
		requestOwn(TRAINER, "VACATION", base.plus(Duration.ofDays(2)), base.plus(Duration.ofDays(4)));
		String approved = requestOwn(OTHER_TRAINER, "SICK_LEAVE", base, base.plus(Duration.ofDays(1)));
		perform(ADMIN, post(ADMIN_PATH + "/" + approved + "/approve"), null).andExpect(status().isOk());
		String window = "&from=" + base.minus(Duration.ofDays(1)) + "&to=" + base.plus(Duration.ofDays(30));

		perform(ADMIN, get(ADMIN_PATH + "?staffId=" + trainerStaffId), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].staffDisplayName").value("Anna Kowalska"));
		String json = perform(ADMIN, get(ADMIN_PATH + "?status=REQUESTED" + window), null)
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<java.util.List<String>>read(json, "$.items[*].status")).containsOnly("REQUESTED");
		assertThat(JsonPath.<java.util.List<String>>read(json, "$.items[*].staffId"))
			.contains(trainerStaffId.toString()).doesNotContain(otherStaffId.toString());
		// Earliest start first.
		perform(ADMIN, get(ADMIN_PATH + "?status=REQUESTED&status=APPROVED&staffId=" + otherStaffId + window), null)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].status").value("APPROVED"));
		perform(ADMIN, get(ADMIN_PATH + "?from=" + base.plus(Duration.ofDays(5)) + "&to=" + base), null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		perform(ADMIN, get(ADMIN_PATH + "?status=SOMETHING"), null).andExpect(status().isBadRequest());
	}

	@Test
	void adminRecordsApprovedTimeOffAlsoInThePast() throws Exception {
		Instant yesterday = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);
		perform(ADMIN, post(ADMIN_PATH), "{\"staffId\":\"" + trainerStaffId + "\",\"kind\":\"SICK_LEAVE\",\"startsAt\":\""
				+ yesterday + "\",\"endsAt\":\"" + yesterday.plus(Duration.ofDays(3)) + "\",\"reason\":\"Telefon\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.decidedAt").exists())
			.andExpect(jsonPath("$.recordedByStudio").value(true));
		perform(ADMIN, post(ADMIN_PATH), "{\"staffId\":\"" + trainerStaffId + "\",\"kind\":\"VACATION\",\"startsAt\":\""
				+ yesterday + "\",\"endsAt\":\"" + yesterday.plus(Duration.ofDays(1)) + "\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TIME_OFF_OVERLAP"));
		perform(ADMIN, post(ADMIN_PATH), "{\"staffId\":\"" + UUID.randomUUID() + "\",\"kind\":\"VACATION\",\"startsAt\":\""
				+ base + "\",\"endsAt\":\"" + base.plus(Duration.ofDays(1)) + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("staffId"));
	}

	@Test
	void trainerCancelsOnlyTheirOwnActiveTimeOff() throws Exception {
		String requested = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(2)));

		perform(OTHER_TRAINER, post(OWN + "/" + requested + "/cancel"), null)
			.andExpect(status().isNotFound());
		perform(TRAINER, post(OWN + "/" + requested + "/cancel"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		perform(TRAINER, post(OWN + "/" + requested + "/cancel"), null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("TIME_OFF_NOT_CANCELLABLE"));
		perform(ADMIN, post(ADMIN_PATH + "/" + requested + "/approve"), null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		String approved = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(2)));
		perform(ADMIN, post(ADMIN_PATH + "/" + approved + "/approve"), null).andExpect(status().isOk());
		perform(TRAINER, post(OWN + "/" + approved + "/cancel"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void timeOffThatHasEndedCannotBeCancelled() throws Exception {
		Instant past = Instant.now().minus(Duration.ofDays(5));
		UUID ended = jdbc.sql("""
				INSERT INTO staff_time_off (staff_id, kind, starts_at, ends_at, status)
				VALUES (:staffId, 'VACATION', :startsAt, :endsAt, 'APPROVED') RETURNING id
				""")
			.param("staffId", trainerStaffId)
			.param("startsAt", java.time.OffsetDateTime.ofInstant(past, java.time.ZoneOffset.UTC))
			.param("endsAt", java.time.OffsetDateTime.ofInstant(past.plus(Duration.ofDays(1)), java.time.ZoneOffset.UTC))
			.query(UUID.class)
			.single();

		perform(TRAINER, post(OWN + "/" + ended + "/cancel"), null)
			.andExpect(status().isUnprocessableContent());
		perform(ADMIN, post(ADMIN_PATH + "/" + ended + "/cancel"), null)
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void adminCancelsApprovedTimeOff() throws Exception {
		String approved = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(2)));
		perform(ADMIN, post(ADMIN_PATH + "/" + approved + "/approve"), null).andExpect(status().isOk());

		perform(ADMIN, post(ADMIN_PATH + "/" + approved + "/cancel"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void trainerChangesOnlyARequestThatWaitsForADecision() throws Exception {
		String requested = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(2)));
		requestOwn(TRAINER, "UNAVAILABLE", base.plus(Duration.ofDays(5)), base.plus(Duration.ofDays(6)));

		perform(TRAINER, patch(OWN + "/" + requested),
				request("SICK_LEAVE", base.plus(Duration.ofDays(1)), base.plus(Duration.ofDays(3)), " Zabieg "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("SICK_LEAVE"))
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.startsAt").value(base.plus(Duration.ofDays(1)).toString()))
			.andExpect(jsonPath("$.reason").value("Zabieg"));
		perform(TRAINER, patch(OWN + "/" + requested),
				request("VACATION", base.plus(Duration.ofDays(4)), base.plus(Duration.ofDays(6)), null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TIME_OFF_OVERLAP"));
		perform(TRAINER, patch(OWN + "/" + requested),
				request("VACATION", base.plus(Duration.ofDays(3)), base.plus(Duration.ofDays(1)), null))
			.andExpect(status().isBadRequest());
		perform(OTHER_TRAINER, patch(OWN + "/" + requested), request("VACATION", base, base.plus(Duration.ofDays(1)), null))
			.andExpect(status().isNotFound());

		perform(ADMIN, post(ADMIN_PATH + "/" + requested + "/approve"), null).andExpect(status().isOk());
		perform(TRAINER, patch(OWN + "/" + requested), request("VACATION", base, base.plus(Duration.ofDays(1)), null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TIME_OFF_ALREADY_DECIDED"))
			.andExpect(jsonPath("$.status").value("APPROVED"));
	}

	@Test
	void studioChangesNotifyTheTrainerButTheTrainersOwnDoNot() throws Exception {
		String approved = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(2)));
		String rejected = requestOwn(TRAINER, "SICK_LEAVE", base.plus(Duration.ofDays(3)), base.plus(Duration.ofDays(4)));
		String withdrawn = requestOwn(TRAINER, "UNAVAILABLE", base.plus(Duration.ofDays(6)),
				base.plus(Duration.ofDays(6)).plus(Duration.ofHours(2)));

		perform(TRAINER, post(OWN + "/" + withdrawn + "/cancel"), null).andExpect(status().isOk());
		perform(ADMIN, post(ADMIN_PATH + "/" + approved + "/approve"), "{\"note\":\"Zastępstwo ustalone\"}")
			.andExpect(status().isOk());
		verify(notifications, timeout(5000)).notifyNow(argThat((requests) -> isFor(requests,
				NotificationType.TIME_OFF_APPROVED, approved)
				&& requests.get(0).values().get("notatka").equals("Notatka studia: Zastępstwo ustalone")
				&& requests.get(0).values().get("rodzaj").equals("Urlop")));
		perform(ADMIN, post(ADMIN_PATH + "/" + rejected + "/reject"), null).andExpect(status().isOk());
		verify(notifications, timeout(5000)).notifyNow(argThat((requests) -> isFor(requests,
				NotificationType.TIME_OFF_REJECTED, rejected) && requests.get(0).values().get("notatka").isEmpty()));
		perform(ADMIN, post(ADMIN_PATH + "/" + approved + "/cancel"), null).andExpect(status().isOk());
		verify(notifications, timeout(5000)).notifyNow(argThat((requests) -> isFor(requests,
				NotificationType.TIME_OFF_CANCELLED, approved)));
		String recorded = JsonPath.read(perform(ADMIN, post(ADMIN_PATH), "{\"staffId\":\"" + trainerStaffId
				+ "\",\"kind\":\"SICK_LEAVE\",\"startsAt\":\"" + base.plus(Duration.ofDays(10)) + "\",\"endsAt\":\""
				+ base.plus(Duration.ofDays(11)) + "\"}")
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");
		verify(notifications, timeout(5000)).notifyNow(argThat((requests) -> isFor(requests,
				NotificationType.TIME_OFF_APPROVED, recorded)));

		verify(notifications, after(500).never()).notifyNow(argThat((requests) -> requests.stream()
			.anyMatch((request) -> request.dedupKey().contains(withdrawn))));
		verify(notifications, never()).notifyNow(argThat((requests) -> requests.size() != 1));
	}

	private boolean isFor(java.util.List<NotificationRequest> requests, NotificationType type, String timeOffId) {
		return requests.size() == 1 && requests.get(0).type() == type
				&& requests.get(0).recipientId().equals(trainerAccountId)
				&& requests.get(0).role() == NotificationRole.TRAINER
				&& requests.get(0).data().get("timeOffId").equals(timeOffId);
	}

	@Test
	void endpointsRequireTheRightRole() throws Exception {
		String requested = requestOwn(TRAINER, "VACATION", base, base.plus(Duration.ofDays(2)));

		perform(null, get(OWN), null).andExpect(status().isUnauthorized());
		perform(CLIENT, get(OWN), null).andExpect(status().isForbidden());
		perform(ADMIN, get(OWN), null).andExpect(status().isForbidden());
		perform(NO_PROFILE, get(OWN), null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(TRAINER, get(ADMIN_PATH), null).andExpect(status().isForbidden());
		perform(TRAINER, post(ADMIN_PATH + "/" + requested + "/approve"), null).andExpect(status().isForbidden());
		perform(CLIENT, post(ADMIN_PATH + "/" + requested + "/cancel"), null).andExpect(status().isForbidden());
	}

	private String requestOwn(String token, String kind, Instant startsAt, Instant endsAt) throws Exception {
		String json = perform(token, post(OWN), request(kind, startsAt, endsAt, null))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.id");
	}

	private static String request(String kind, Instant startsAt, Instant endsAt, String reason) {
		String reasonJson = reason == null ? "null" : "\"" + reason + "\"";
		return "{\"kind\":\"" + kind + "\",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\"" + endsAt
				+ "\",\"reason\":" + reasonJson + "}";
	}

	private long countFor(UUID staffId) {
		return jdbc.sql("SELECT count(*) FROM staff_time_off WHERE staff_id = :staffId")
			.param("staffId", staffId)
			.query(Long.class)
			.single();
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private UUID addAccount(String name, String token, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", "time-off-" + name + "@example.com")
			.query(UUID.class)
			.single();
		String uid = "uid-time-off-" + name;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", uid)
			.update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
				.param("id", id)
				.param("role", role)
				.update();
		}
		given(jwtDecoder.decode(token))
			.willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID addStaffProfile(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name)
				VALUES (:accountId, :firstName, :lastName) RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.query(UUID.class)
			.single();
	}

}
