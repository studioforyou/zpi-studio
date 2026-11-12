package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.notification.NotificationDispatcher;
import studioforyou.pl.backend.communication.push.PushMessage;
import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;
import studioforyou.pl.backend.communication.reminder.ClassReminders;

/**
 * Class reminders, push delivery, device registration, the in-app inbox and the administrator's
 * notification settings, against real PostgreSQL. Push sending is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class NotificationTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ClassReminders classReminders;

	@Autowired
	private NotificationDispatcher dispatcher;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private PushSender pushSender;

	private UUID adultAccount;

	private UUID adult;

	private UUID parentAccount;

	private UUID parent;

	private UUID child;

	private UUID trainerClientAccount;

	private UUID trainerClient;

	@BeforeEach
	void setUp() {
		given(pushSender.send(any())).willAnswer((call) -> PushResult.sent("msg-" + UUID.randomUUID()));
		adultAccount = addAccount("adult", "CLIENT");
		adult = addProfile(adultAccount, "ADULT", "Ala");
		parentAccount = addAccount("parent", "CLIENT");
		parent = addProfile(parentAccount, "ADULT", "Monika");
		child = addProfile(null, "CHILD", "Zosia");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", parent).param("child", child).update();
		trainerClientAccount = addAccount("trainer-client", "CLIENT", "TRAINER");
		trainerClient = addProfile(trainerClientAccount, "ADULT", "Karolina");
		addAccount("admin", "ADMIN");
	}

	// --- class reminders ---

	@Test
	void remindsEveryParticipantOnceAtEachReminderTime() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		UUID event = addEvent("Pilates", now.plus(120, ChronoUnit.MINUTES), "SCHEDULED", "Sala 1");
		UUID adultReservation = reserve(event, adult, "ACTIVE");
		reserve(event, child, "ACTIVE");
		reserve(event, parent, "ACTIVE");
		reserve(event, trainerClient, "ACTIVE");
		reserve(addEvent("Later", now.plus(180, ChronoUnit.MINUTES), "SCHEDULED", null), adult, "ACTIVE");

		assertThat(classReminders.sendDue(now)).isEqualTo(4);

		assertThat(titles(adultAccount)).containsExactly("Przypomnienie o zajęciach");
		// After 22:00 in Warsaw a class two hours ahead is already tomorrow.
		ZoneId warsaw = ZoneId.of("Europe/Warsaw");
		ZonedDateTime start = now.plus(120, ChronoUnit.MINUTES).atZone(warsaw);
		String day = start.toLocalDate().equals(now.atZone(warsaw).toLocalDate()) ? "dziś" : "jutro";
		assertThat(bodies(adultAccount)).containsExactly("Pilates: " + day + ", godz. "
				+ start.format(DateTimeFormatter.ofPattern("HH:mm")) + ". Sala: Sala 1.");
		// A guardian sees whose class it is, also for its own.
		assertThat(titles(parentAccount)).containsExactlyInAnyOrder("Zosia: Przypomnienie o zajęciach",
				"Monika: Przypomnienie o zajęciach");
		assertThat(jdbc.sql("SELECT subject_customer_id FROM notification WHERE recipient_id = :id AND subject_customer_id IS NOT NULL")
			.param("id", parentAccount).query(UUID.class).single()).isEqualTo(child);
		assertThat(titles(trainerClientAccount)).containsExactly("Klient: Przypomnienie o zajęciach");
		assertThat(jdbc.sql("SELECT dedup_key FROM notification WHERE recipient_id = :id")
			.param("id", adultAccount).query(String.class).single())
			.isEqualTo("CLASS_REMINDER:" + adultReservation + ":120:" + adultAccount);

		// A later job run in the grace period creates nothing new.
		assertThat(classReminders.sendDue(now.plus(5, ChronoUnit.MINUTES))).isZero();
	}

	@Test
	void skipsCancelledReservationsCancelledClassesAndASwitchedOffType() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		UUID event = addEvent("Pilates", now.plus(120, ChronoUnit.MINUTES), "SCHEDULED", null);
		reserve(event, adult, "CANCELLED");
		reserve(addEvent("Cancelled", now.plus(120, ChronoUnit.MINUTES), "CANCELLED", null), trainerClient, "ACTIVE");
		assertThat(classReminders.sendDue(now)).isZero();

		reserve(event, child, "ACTIVE");
		jdbc.sql("UPDATE notification_type SET is_enabled = FALSE WHERE code = 'CLASS_REMINDER'").update();
		assertThat(classReminders.sendDue(now)).isZero();
		jdbc.sql("UPDATE notification_type SET is_enabled = TRUE WHERE code = 'CLASS_REMINDER'").update();
		assertThat(classReminders.sendDue(now)).isEqualTo(1);
	}

	@Test
	void usesTheAdministratorsReminderTimes() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		reserve(addEvent("Yoga", now.plus(45, ChronoUnit.MINUTES), "SCHEDULED", null), adult, "ACTIVE");
		assertThat(classReminders.sendDue(now)).isZero();
		jdbc.sql("INSERT INTO notification_lead_time (notification_type_id, minutes_before) VALUES (1, 45)").update();
		assertThat(classReminders.sendDue(now)).isEqualTo(1);
	}

	// --- delivery ---

	@Test
	void sendsToEveryActiveDeviceAndRevokesInvalidTokens() throws Exception {
		register("adult", "token-phone", "ANDROID").andExpect(status().isOk());
		register("adult", "token-old", "ANDROID").andExpect(status().isOk());
		given(pushSender.send(any())).willAnswer((call) -> {
			PushMessage message = call.getArgument(0);
			return message.token().equals("token-old") ? PushResult.invalidToken("UNREGISTERED")
					: PushResult.sent("msg-1");
		});
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		UUID event = addEvent("Pilates", now.plus(120, ChronoUnit.MINUTES), "SCHEDULED", null);
		UUID reservation = reserve(event, adult, "ACTIVE");

		classReminders.sendDue(now);

		ArgumentCaptor<PushMessage> sent = ArgumentCaptor.forClass(PushMessage.class);
		verify(pushSender, times(2)).send(sent.capture());
		PushMessage phone = sent.getAllValues().stream().filter((m) -> m.token().equals("token-phone")).findFirst()
			.orElseThrow();
		assertThat(phone.title()).isEqualTo("Przypomnienie o zajęciach");
		assertThat(phone.data()).containsEntry("type", "CLASS_REMINDER").containsEntry("eventId", event.toString())
			.containsEntry("reservationId", reservation.toString()).containsEntry("profileId", adult.toString())
			.containsKey("notificationId");
		assertThat(jdbc.sql("SELECT status FROM notification_delivery ORDER BY status").query(String.class).list())
			.containsExactly("SENT", "TOKEN_INVALID");
		assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM device_token WHERE token = 'token-old'")
			.query(Boolean.class).single()).isTrue();
	}

	@Test
	void retriesFailedDeliveriesButNotDeliveredOnes() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		jdbc.sql("""
				INSERT INTO device_token (account_id, token, platform) VALUES (:id, 'token-a', 'ANDROID')
				""").param("id", adultAccount).update();
		UUID failed = addNotification(adultAccount, now.minus(10, ChronoUnit.MINUTES));
		jdbc.sql("INSERT INTO notification_delivery (notification_id, channel, status) VALUES (:id, 'PUSH', 'FAILED')")
			.param("id", failed).update();
		UUID delivered = addNotification(adultAccount, now.minus(10, ChronoUnit.MINUTES));
		jdbc.sql("INSERT INTO notification_delivery (notification_id, channel, status) VALUES (:id, 'PUSH', 'SENT')")
			.param("id", delivered).update();
		UUID noDevice = addNotification(parentAccount, now.minus(10, ChronoUnit.MINUTES));
		jdbc.sql("INSERT INTO notification_delivery (notification_id, channel, status) VALUES (:id, 'PUSH', 'NO_DEVICE')")
			.param("id", noDevice).update();

		// The rows were created at the transaction's start, i.e. right now; the job leaves them for 2 minutes.
		assertThat(dispatcher.retryUndelivered(now)).isZero();
		assertThat(dispatcher.retryUndelivered(now.plus(3, ChronoUnit.MINUTES))).isEqualTo(1);
		verify(pushSender, times(1)).send(any());
	}

	@Test
	void keepsANotificationWithoutADeviceInTheInbox() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		reserve(addEvent("Pilates", now.plus(120, ChronoUnit.MINUTES), "SCHEDULED", null), adult, "ACTIVE");
		classReminders.sendDue(now);
		verify(pushSender, never()).send(any());
		assertThat(jdbc.sql("SELECT status FROM notification_delivery").query(String.class).list())
			.containsExactly("NO_DEVICE");
	}

	// --- devices ---

	@Test
	void aTokenMovesToTheAccountThatSignedInLastAndIsRevokedOnSignOut() throws Exception {
		register("adult", "shared-phone", "IOS").andExpect(status().isOk())
			.andExpect(jsonPath("$.platform").value("IOS"));
		register("parent", "shared-phone", "ANDROID").andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT account_id FROM device_token WHERE token = 'shared-phone'").query(UUID.class)
			.single()).isEqualTo(parentAccount);

		// Another account cannot revoke it.
		mvc.perform(withToken(delete("/api/v1/me/devices/shared-phone"), "adult")).andExpect(status().isNoContent());
		assertThat(revoked("shared-phone")).isFalse();
		mvc.perform(withToken(delete("/api/v1/me/devices/shared-phone"), "parent")).andExpect(status().isNoContent());
		assertThat(revoked("shared-phone")).isTrue();

		register("parent", "shared-phone", "ANDROID").andExpect(status().isOk());
		assertThat(revoked("shared-phone")).isFalse();
	}

	@Test
	void validatesTheDevice() throws Exception {
		mvc.perform(withToken(post("/api/v1/me/devices"), "adult").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\": \"t\"}")).andExpect(status().isBadRequest());
		mvc.perform(withToken(post("/api/v1/me/devices"), "adult").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\": \"t\", \"platform\": \"WINDOWS\"}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/me/devices").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\": \"t\", \"platform\": \"IOS\"}")).andExpect(status().isUnauthorized());
	}

	// --- inbox ---

	@Test
	void listsAndMarksOwnDueNotifications() throws Exception {
		Instant now = Instant.now();
		UUID older = addNotification(trainerClientAccount, now.minus(2, ChronoUnit.HOURS));
		UUID newer = addNotification(trainerClientAccount, now.minus(1, ChronoUnit.HOURS));
		UUID trainerOnly = addNotification(trainerClientAccount, now.minus(30, ChronoUnit.MINUTES));
		jdbc.sql("UPDATE notification SET recipient_role = 'TRAINER' WHERE id = :id").param("id", trainerOnly).update();
		addNotification(trainerClientAccount, now.plus(1, ChronoUnit.HOURS)); // not due yet
		UUID foreign = addNotification(adultAccount, now.minus(1, ChronoUnit.HOURS));

		mvc.perform(withToken(get("/api/v1/me/notifications"), "trainer-client")).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(3))
			.andExpect(jsonPath("$.items[0].id").value(trainerOnly.toString()))
			.andExpect(jsonPath("$.items[1].id").value(newer.toString()))
			.andExpect(jsonPath("$.items[1].type").value("CLASS_REMINDER"))
			.andExpect(jsonPath("$.items[1].role").value("CLIENT"))
			.andExpect(jsonPath("$.items[1].data.eventId").value("e-1"))
			.andExpect(jsonPath("$.items[1].readAt").doesNotExist());
		mvc.perform(withToken(get("/api/v1/me/notifications?role=CLIENT"), "trainer-client"))
			.andExpect(jsonPath("$.items", hasSize(2)));
		mvc.perform(withToken(get("/api/v1/me/notifications/unread-count"), "trainer-client"))
			.andExpect(jsonPath("$.count").value(3));

		mvc.perform(withToken(post("/api/v1/me/notifications/" + older + "/read"), "trainer-client"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isString());
		mvc.perform(withToken(post("/api/v1/me/notifications/" + foreign + "/read"), "trainer-client"))
			.andExpect(status().isNotFound());
		mvc.perform(withToken(post("/api/v1/me/notifications/read-all?role=CLIENT"), "trainer-client"))
			.andExpect(jsonPath("$.markedRead").value(1));
		mvc.perform(withToken(get("/api/v1/me/notifications/unread-count"), "trainer-client"))
			.andExpect(jsonPath("$.count").value(1));
	}

	@Test
	void theInboxKeepsDifferentNotificationTypesRegardlessOfPushDelivery() throws Exception {
		Instant now = Instant.now();
		UUID reminder = addNotification(adultAccount, now.minus(3, ChronoUnit.MINUTES));
		UUID pass = addNotification(adultAccount, now.minus(2, ChronoUnit.MINUTES));
		UUID waitlist = addNotification(adultAccount, now.minus(1, ChronoUnit.MINUTES));
		jdbc.sql("UPDATE notification SET notification_type_id = 2 WHERE id = :id").param("id", pass).update();
		jdbc.sql("UPDATE notification SET notification_type_id = 5 WHERE id = :id").param("id", waitlist).update();
		jdbc.sql("INSERT INTO notification_delivery (notification_id, channel, status) VALUES (:id, 'PUSH', 'SENT')").param("id", reminder).update();
		jdbc.sql("INSERT INTO notification_delivery (notification_id, channel, status) VALUES (:id, 'PUSH', 'NO_DEVICE')").param("id", pass).update();
		jdbc.sql("INSERT INTO notification_delivery (notification_id, channel, status) VALUES (:id, 'PUSH', 'FAILED')").param("id", waitlist).update();
		mvc.perform(withToken(get("/api/v1/me/notifications?role=CLIENT"), "adult"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(3))
			.andExpect(jsonPath("$.items[0].type").value("WAITLIST_PLACE_OFFERED"))
			.andExpect(jsonPath("$.items[1].type").value("PASS_EXPIRING"))
			.andExpect(jsonPath("$.items[2].type").value("CLASS_REMINDER"));
		mvc.perform(withToken(post("/api/v1/me/notifications/" + pass + "/read"), "adult"))
			.andExpect(status().isOk());
		mvc.perform(withToken(get("/api/v1/me/notifications?role=CLIENT"), "adult"))
			.andExpect(jsonPath("$.totalItems").value(3)).andExpect(jsonPath("$.items[1].readAt").isString());
		mvc.perform(withToken(get("/api/v1/me/notifications/unread-count?role=CLIENT"), "adult"))
			.andExpect(jsonPath("$.count").value(2));
	}

	// --- administrator settings ---

	@Test
	void theAdministratorConfiguresAType() throws Exception {
		mvc.perform(withToken(get("/api/v1/notification-types"), "admin")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].code").value("CLASS_REMINDER"))
			.andExpect(jsonPath("$.items[0].enabled").value(true))
			.andExpect(jsonPath("$.items[0].timed").value(true))
			.andExpect(jsonPath("$.items[0].leadTimesMinutes[0]").value(1440))
			.andExpect(jsonPath("$.items[0].leadTimesMinutes[1]").value(120))
			.andExpect(jsonPath("$.items[0].title").value("Przypomnienie o zajęciach"))
			.andExpect(jsonPath("$.items[0].body").value("{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}."))
			.andExpect(jsonPath("$.items[0].placeholders[0].name").value("zajęcia"))
			.andExpect(jsonPath("$.items[0].placeholders[4].name").value("ile przed"));

		update("{\"enabled\": false, \"leadTimesMinutes\": [60, 2880], \"title\": \" Już {{ile przed}}! \", "
				+ "\"body\": \"{{zajęcia}} o {{godzina}}\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(false))
			.andExpect(jsonPath("$.leadTimesMinutes[0]").value(2880))
			.andExpect(jsonPath("$.leadTimesMinutes[1]").value(60))
			.andExpect(jsonPath("$.title").value("Już {{ile przed}}!"));
		update("{\"enabled\": true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.enabled").value(true))
			.andExpect(jsonPath("$.body").value("{{zajęcia}} o {{godzina}}"));
	}

	@Test
	void rejectsInvalidSettings() throws Exception {
		update("{\"body\": \"{{zajęcia}} {{price}}\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("body"));
		update("{\"leadTimesMinutes\": [60, 60]}").andExpect(status().isBadRequest());
		update("{\"leadTimesMinutes\": [1]}").andExpect(status().isBadRequest());
		update("{\"leadTimesMinutes\": []}").andExpect(status().isBadRequest());
		update("{\"title\": \"   \"}").andExpect(status().isBadRequest());
		mvc.perform(withToken(patch("/api/v1/notification-types/UNKNOWN"), "admin")
			.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
		mvc.perform(withToken(get("/api/v1/notification-types"), "adult")).andExpect(status().isForbidden());
	}

	@Test
	void theJobEndpointRejectsUserTokens() throws Exception {
		mvc.perform(post("/internal/jobs/notifications/run")).andExpect(status().isUnauthorized());
		mvc.perform(withToken(post("/internal/jobs/notifications/run"), "admin")).andExpect(status().isUnauthorized());
	}

	// --- helpers ---

	private ResultActions update(String body) throws Exception {
		return mvc.perform(withToken(patch("/api/v1/notification-types/CLASS_REMINDER"), "admin")
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions register(String user, String token, String platform)
			throws Exception {
		return mvc.perform(withToken(post("/api/v1/me/devices"), user).contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\": \"" + token + "\", \"platform\": \"" + platform + "\"}"));
	}

	private boolean revoked(String token) {
		return jdbc.sql("SELECT revoked_at IS NOT NULL FROM device_token WHERE token = :token").param("token", token)
			.query(Boolean.class).single();
	}

	private List<String> titles(UUID account) {
		return jdbc.sql("SELECT title FROM notification WHERE recipient_id = :id").param("id", account)
			.query(String.class).list();
	}

	private List<String> bodies(UUID account) {
		return jdbc.sql("SELECT body FROM notification WHERE recipient_id = :id").param("id", account)
			.query(String.class).list();
	}

	private UUID addNotification(UUID account, Instant scheduledFor) {
		return jdbc.sql("""
				INSERT INTO notification (recipient_id, notification_type_id, recipient_role, title, body, payload,
				  dedup_key, scheduled_for)
				VALUES (:account, 1, 'CLIENT', 'Title', 'Body', '{"eventId": "e-1"}', :key, :scheduledFor)
				RETURNING id
				""")
			.param("account", account)
			.param("key", "test:" + UUID.randomUUID())
			.param("scheduledFor", scheduledFor.atOffset(ZoneOffset.UTC))
			.query(UUID.class)
			.single();
	}

	private UUID addEvent(String title, Instant startsAt, String status, String room) {
		UUID event = jdbc.sql("""
				INSERT INTO schedule_event (kind, title, starts_at, ends_at, capacity, visibility, status)
				VALUES ('CLASS', :title, :startsAt, :endsAt, 12, 'PUBLIC', :status)
				RETURNING id
				""")
			.param("title", title)
			.param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
			.param("endsAt", startsAt.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
			.param("status", status)
			.query(UUID.class)
			.single();
		if (room != null) {
			UUID resource = jdbc.sql("INSERT INTO resource (name) VALUES (:name) RETURNING id")
				.param("name", room).query(UUID.class).single();
			jdbc.sql("INSERT INTO event_resource (event_id, resource_id) VALUES (:event, :resource)")
				.param("event", event).param("resource", resource).update();
		}
		return event;
	}

	private UUID reserve(UUID event, UUID customer, String status) {
		return jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status)
				VALUES (:event, :customer, :bookedBy, :status)
				RETURNING id
				""")
			.param("event", event)
			.param("customer", customer)
			.param("bookedBy", adultAccount)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

	private UUID addAccount(String name, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@notifications.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", "notifications-" + name).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
				.param("id", id).param("code", role).update();
		}
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256")
			.subject("notifications-" + name).build());
		return id;
	}

	private UUID addProfile(UUID account, String kind, String firstName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, :firstName, 'Tester') RETURNING id
				""").param("account", account, java.sql.Types.OTHER).param("kind", kind).param("firstName", firstName)
			.query(UUID.class).single();
	}

	private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
		return request.header("Authorization", "Bearer " + token);
	}

}
