package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

import studioforyou.pl.backend.NotificationCleanup;
import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.booking.ClassAutoCancellation;

/**
 * Administrators force a group class to run although too few participants booked it, and switch the
 * automatic cancellation off for every class of a fixed group (#136). Firebase ID tokens are mocked
 * at the {@link JwtDecoder}; requests run in their own transactions and the data is removed after
 * each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventAutoCancellationTests {

	private static final String ADMIN_TOKEN = "auto-cancel-override-admin-token";

	private static final String CLIENT_TOKEN = "auto-cancel-override-client-token";

	private static final String EMAIL_DOMAIN = "@auto-cancel-override.test";

	private static final String ACCOUNTS = "SELECT id FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'";

	private static final String EVENTS = "SELECT id FROM schedule_event WHERE service_type_id = :service";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ClassAutoCancellation autoCancellation;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID admin;

	private UUID client;

	private UUID participant;

	private UUID service;

	private UUID group;

	private Instant now;

	@BeforeEach
	void setUp() {
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		admin = account("admin", "ADMIN", ADMIN_TOKEN);
		client = account("client", "CLIENT", CLIENT_TOKEN);
		participant = jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, birth_date, account_id)
				VALUES ('ADULT', 'Ola', 'Uczestniczka', :birthDate, :account) RETURNING id
				""").param("birthDate", LocalDate.now().minusYears(30)).param("account", client)
			.query(UUID.class).single();
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, max_participants)
				VALUES ('Auto-cancel override yoga', 'GROUP', 60, 12) RETURNING id
				""").query(UUID.class).single();
		// Checked 3 hours before the start; at least 3 participants.
		jdbc.sql("""
				INSERT INTO booking_policy (scope, service_type_id, auto_cancel_enabled, auto_cancel_min_participants,
				    auto_cancel_hours_before)
				VALUES ('SERVICE_TYPE', :service, TRUE, 3, 3)
				""").param("service", service).update();
		group = jdbc.sql("""
				INSERT INTO training_group (name, service_type_id) VALUES ('Auto-cancel override group', :service)
				RETURNING id
				""").param("service", service).query(UUID.class).single();
	}

	@AfterEach
	void cleanUp() {
		NotificationCleanup.deleteNotifications(jdbc, ACCOUNTS);
		jdbc.sql("DELETE FROM audit_log WHERE entity_id IN (" + EVENTS + ")").param("service", service).update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + EVENTS + ")").param("service", service).update();
		jdbc.sql("DELETE FROM booking_policy WHERE service_type_id = :service OR training_group_id = :group "
				+ "OR event_id IN (" + EVENTS + ")").param("service", service).param("group", group).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM training_group WHERE id = :group").param("group", group).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (" + ACCOUNTS + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + ACCOUNTS + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + ACCOUNTS + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'").update();
	}

	@Test
	void anAdministratorSeesWhenTheParticipantsAreCountedAndForcesTheClassToRun() throws Exception {
		Instant startsAt = now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(1));
		UUID event = event(startsAt, null);
		reserve(event);

		perform(get(path(event)), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.eventId").value(event.toString()))
			.andExpect(jsonPath("$.state").value("PENDING"))
			.andExpect(jsonPath("$.ruleEnabled").value(true))
			.andExpect(jsonPath("$.forceRun").value(false))
			.andExpect(jsonPath("$.trainingGroupId").value(nullValue()))
			.andExpect(jsonPath("$.disabledForGroup").value(false))
			.andExpect(jsonPath("$.participants").value(1))
			.andExpect(jsonPath("$.minimumParticipants").value(3))
			.andExpect(jsonPath("$.checkAt").value(startsAt.minus(Duration.ofHours(3)).toString()));
		int version = version(event);

		perform(put(path(event)).contentType(MediaType.APPLICATION_JSON).content("{\"forceRun\":true}"), ADMIN_TOKEN)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("FORCED"))
			.andExpect(jsonPath("$.forceRun").value(true))
			.andExpect(jsonPath("$.ruleEnabled").value(true))
			.andExpect(jsonPath("$.minimumParticipants").value(3));
		// Repeating it changes nothing.
		perform(put(path(event)).contentType(MediaType.APPLICATION_JSON).content("{\"forceRun\":true}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FORCED"));

		assertThat(version(event)).isEqualTo(version);
		assertThat(jdbc.sql("""
				SELECT actor_account_id::text || ' ' || action || ' ' || changes::text FROM audit_log
				WHERE entity_id = :id ORDER BY id
				""").param("id", event).query(String.class).list())
			.containsExactly(admin + " EVENT_FORCE_RUN_CHANGED {\"forceRun\": {\"new\": true, \"old\": false}}");
		assertThat(autoCancellation.cancelDue(now)).isZero();
		assertThat(eventStatus(event)).isEqualTo("SCHEDULED");
		assertThat(jdbc.sql("SELECT status FROM reservation WHERE event_id = :id").param("id", event)
			.query(String.class).single()).isEqualTo("ACTIVE");

		// Back to the rule: the next job run cancels the class.
		perform(put(path(event)).contentType(MediaType.APPLICATION_JSON).content("{\"forceRun\":false}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"))
			.andExpect(jsonPath("$.forceRun").value(false));
		assertThat(autoCancellation.cancelDue(now)).isEqualTo(1);
		assertThat(eventStatus(event)).isEqualTo("CANCELLED");

		// A class cancelled automatically stays cancelled.
		perform(put(path(event)).contentType(MediaType.APPLICATION_JSON).content("{\"forceRun\":true}"), ADMIN_TOKEN)
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
		perform(get(path(event)), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CLOSED"));
	}

	@Test
	void anAdministratorSwitchesTheRuleOffForEveryClassOfAGroupAndKeepsItsOtherRules() throws Exception {
		jdbc.sql("""
				INSERT INTO booking_policy (scope, training_group_id, booking_closes_minutes_before)
				VALUES ('GROUP', :group, 45)
				""").param("group", group).update();
		UUID first = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(1)), group);
		UUID second = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(2)), group);
		UUID other = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(3)), null);

		perform(get(groupPath()), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.trainingGroupId").value(group.toString()))
			.andExpect(jsonPath("$.disabled").value(false));
		perform(put(groupPath()).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(true));
		perform(put(groupPath()).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(true));

		perform(get(path(first)), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("OFF"))
			.andExpect(jsonPath("$.ruleEnabled").value(false))
			.andExpect(jsonPath("$.trainingGroupId").value(group.toString()))
			.andExpect(jsonPath("$.disabledForGroup").value(true))
			.andExpect(jsonPath("$.minimumParticipants").value(nullValue()))
			.andExpect(jsonPath("$.checkAt").value(nullValue()));
		// A new version of the group's policy, created by the administrator, with its other rules.
		assertThat(jdbc.sql("""
				SELECT (valid_to IS NULL)::text || ' ' || coalesce(auto_cancel_enabled::text, '-') || ' '
				    || booking_closes_minutes_before || ' ' || coalesce(created_by::text, '-')
				FROM booking_policy WHERE training_group_id = :group ORDER BY created_at, valid_to NULLS LAST
				""").param("group", group).query(String.class).list())
			.containsExactly("false - 45 -", "true false 45 " + admin);

		assertThat(autoCancellation.cancelDue(now)).isEqualTo(1);
		assertThat(List.of(eventStatus(first), eventStatus(second), eventStatus(other)))
			.containsExactly("SCHEDULED", "SCHEDULED", "CANCELLED");

		// Back to the service's rule.
		perform(put(groupPath()).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":false}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(false));
		perform(get(path(first)), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PENDING"))
			.andExpect(jsonPath("$.disabledForGroup").value(false));
		assertThat(jdbc.sql("""
				SELECT coalesce(auto_cancel_enabled::text, '-') || ' ' || booking_closes_minutes_before
				FROM booking_policy WHERE training_group_id = :group AND valid_to IS NULL
				""").param("group", group).query(String.class).list()).containsExactly("- 45");
		assertThat(autoCancellation.cancelDue(now)).isEqualTo(2);
	}

	@Test
	void switchingTheRuleBackOnForAGroupWithoutOtherRulesLeavesItWithoutAPolicy() throws Exception {
		perform(put(groupPath()).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}"), ADMIN_TOKEN)
			.andExpect(status().isOk());
		perform(put(groupPath()).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":false}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.disabled").value(false));

		assertThat(jdbc.sql("SELECT count(*) FROM booking_policy WHERE training_group_id = :group AND valid_to IS NULL")
			.param("group", group).query(Integer.class).single()).isZero();
	}

	@Test
	void aClassWithoutTheRuleIsOffAndAStartedClassCannotBeForced() throws Exception {
		UUID withoutRule = event(now.plus(Duration.ofHours(2)), null);
		jdbc.sql("INSERT INTO booking_policy (scope, event_id, auto_cancel_enabled) VALUES ('EVENT', :event, FALSE)")
			.param("event", withoutRule).update();
		perform(get(path(withoutRule)), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("OFF"))
			.andExpect(jsonPath("$.ruleEnabled").value(false))
			.andExpect(jsonPath("$.disabledForGroup").value(false));

		// Counted 30 minutes ago and kept: the check is over.
		UUID checked = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(31)), null);
		perform(get(path(checked)), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CLOSED"));

		UUID started = event(now.minus(Duration.ofMinutes(10)), null);
		perform(put(path(started)).contentType(MediaType.APPLICATION_JSON).content("{\"forceRun\":true}"), ADMIN_TOKEN)
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
		assertThat(jdbc.sql("SELECT force_run FROM schedule_event WHERE id = :id").param("id", started)
			.query(Boolean.class).single()).isFalse();
	}

	@Test
	void onlyAdministratorsOverrideTheRuleOfExistingClassesAndGroups() throws Exception {
		UUID event = event(now.plus(Duration.ofHours(5)), group);

		perform(get(path(event)), CLIENT_TOKEN).andExpect(status().isForbidden());
		perform(put(path(event)).contentType(MediaType.APPLICATION_JSON).content("{\"forceRun\":true}"), CLIENT_TOKEN)
			.andExpect(status().isForbidden());
		perform(put(groupPath()).contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}"), CLIENT_TOKEN)
			.andExpect(status().isForbidden());

		perform(put(path(event)).contentType(MediaType.APPLICATION_JSON).content("{}"), ADMIN_TOKEN)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		perform(put(groupPath()).contentType(MediaType.APPLICATION_JSON).content("{}"), ADMIN_TOKEN)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		perform(get(path(UUID.randomUUID())), ADMIN_TOKEN).andExpect(status().isNotFound());
		perform(put("/api/v1/training-groups/" + UUID.randomUUID() + "/auto-cancellation")
			.contentType(MediaType.APPLICATION_JSON).content("{\"disabled\":true}"), ADMIN_TOKEN)
			.andExpect(status().isNotFound());

		assertThat(jdbc.sql("SELECT force_run FROM schedule_event WHERE id = :id").param("id", event)
			.query(Boolean.class).single()).isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM booking_policy WHERE training_group_id = :group")
			.param("group", group).query(Integer.class).single()).isZero();
	}

	private String path(UUID event) {
		return "/api/v1/schedule-events/" + event + "/auto-cancellation";
	}

	private String groupPath() {
		return "/api/v1/training-groups/" + group + "/auto-cancellation";
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String token) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + token));
	}

	private UUID account(String name, String role, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + EMAIL_DOMAIN).query(UUID.class).single();
		String uid = "auto-cancel-override-" + id;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id).param("role", role).update();
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID event(Instant startsAt, UUID trainingGroup) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, training_group_id, title, starts_at, ends_at, capacity,
				    visibility)
				VALUES ('CLASS', :service, :group, 'Auto-cancel override class', :startsAt, :endsAt, 12, 'PUBLIC')
				RETURNING id
				""").param("service", service).param("group", trainingGroup).param("startsAt", utc(startsAt))
			.param("endsAt", utc(startsAt.plus(Duration.ofHours(1)))).query(UUID.class).single();
	}

	private void reserve(UUID event) {
		jdbc.sql("INSERT INTO reservation (event_id, customer_id, booked_by) VALUES (:event, :customer, :account)")
			.param("event", event).param("customer", participant).param("account", client).update();
	}

	private String eventStatus(UUID event) {
		return jdbc.sql("SELECT status FROM schedule_event WHERE id = :id").param("id", event)
			.query(String.class).single();
	}

	private int version(UUID event) {
		return jdbc.sql("SELECT version FROM schedule_event WHERE id = :id").param("id", event)
			.query(Integer.class).single();
	}

	private static OffsetDateTime utc(Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}

}
