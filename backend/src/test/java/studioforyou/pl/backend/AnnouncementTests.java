package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.notification.NotificationDispatcher;
import studioforyou.pl.backend.communication.push.PushMessage;
import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;

/**
 * Announcements the administrator sends from the panel (#157): who gets them, the marketing
 * consent, the in-app inbox, the push and the history, against real PostgreSQL. Push sending is
 * mocked; the background push after the commit is not part of these rolled-back tests, so they
 * send the stored notifications with the dispatcher themselves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AnnouncementTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private NotificationDispatcher dispatcher;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private PushSender pushSender;

	private UUID adminAccount;

	private UUID adultAccount;

	private UUID adult;

	private UUID parentAccount;

	private UUID child;

	private UUID trainerClientAccount;

	private UUID trainerClient;

	private UUID trainerAccount;

	private UUID group;

	@BeforeEach
	void setUp() {
		given(pushSender.send(any())).willAnswer((call) -> PushResult.sent("msg-" + UUID.randomUUID()));
		adminAccount = addAccount("admin", "ADMIN");
		addProfile(adminAccount, "ADULT", "Beata", "Lis");
		adultAccount = addAccount("adult", "CLIENT");
		adult = addProfile(adultAccount, "ADULT", "Ala", "Kot");
		parentAccount = addAccount("parent", "CLIENT");
		UUID parent = addProfile(parentAccount, "ADULT", "Monika", "Lis");
		child = addProfile(null, "CHILD", "Zosia", "Lis");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", parent).param("child", child).update();
		trainerClientAccount = addAccount("trainer-client", "CLIENT", "TRAINER");
		trainerClient = addProfile(trainerClientAccount, "ADULT", "Karolina", "Nowak");
		trainerAccount = addAccount("trainer", "TRAINER");
		UUID service = jdbc.sql("INSERT INTO service_type (name, category, default_duration_minutes) VALUES ('Pole dance', 'GROUP_CLASS', 60) RETURNING id")
			.query(UUID.class).single();
		group = jdbc.sql("INSERT INTO training_group (name, service_type_id) VALUES ('Pole dance początkujący', :service) RETURNING id")
			.param("service", service).query(UUID.class).single();
	}

	// --- audiences ---

	@Test
	void groupAnnouncementReachesMembersParticipantsAndGuardiansOnce() throws Exception {
		joinGroup(adult);
		joinGroup(child);
		UUID upcoming = addEvent("Pole dance", Instant.now().plus(2, ChronoUnit.DAYS), "SCHEDULED");
		jdbc.sql("UPDATE schedule_event SET training_group_id = :group WHERE id = :id")
			.param("group", group).param("id", upcoming).update();
		reserve(upcoming, trainerClient);
		reserve(upcoming, adult);

		preview(Map.of("audience", "GROUP", "trainingGroupId", group))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recipients").value(3))
			.andExpect(jsonPath("$.guardians").value(1))
			.andExpect(jsonPath("$.skippedWithoutConsent").value(0));

		send(Map.of("audience", "GROUP", "trainingGroupId", group, "title", " Zmiana sali ",
				"body", "W czwartek ćwiczymy w Sali 2."))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("Zmiana sali"))
			.andExpect(jsonPath("$.audience").value("GROUP"))
			.andExpect(jsonPath("$.targetName").value("Pole dance początkujący"))
			.andExpect(jsonPath("$.authorName").value("Beata Lis"))
			.andExpect(jsonPath("$.recipients").value(3))
			.andExpect(jsonPath("$.read").value(0));

		assertThat(titles(adultAccount)).containsExactly("Zmiana sali");
		assertThat(titles(parentAccount)).containsExactly("Zosia: Zmiana sali");
		assertThat(titles(trainerClientAccount)).containsExactly("Klient: Zmiana sali");
		assertThat(jdbc.sql("SELECT subject_customer_id FROM notification WHERE recipient_id = :id")
			.param("id", parentAccount).query(UUID.class).single()).isEqualTo(child);
		assertThat(jdbc.sql("SELECT payload ->> 'audience' FROM notification WHERE recipient_id = :id")
			.param("id", adultAccount).query(String.class).single()).isEqualTo("grupa Pole dance początkujący");
	}

	@Test
	void eventAnnouncementReachesItsParticipantsOnly() throws Exception {
		UUID event = addEvent("Warsztaty", Instant.now().plus(3, ChronoUnit.DAYS), "SCHEDULED");
		reserve(event, adult);
		reserve(event, child);
		UUID cancelled = jdbc.sql("SELECT id FROM reservation WHERE event_id = :event AND customer_id = :child")
			.param("event", event).param("child", child).query(UUID.class).single();
		jdbc.sql("UPDATE reservation SET status = 'CANCELLED' WHERE id = :id").param("id", cancelled).update();

		send(Map.of("audience", "EVENT", "eventId", event, "title", "Co zabrać", "body", "Wodę i ręcznik."))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.targetName").value("Warsztaty"))
			.andExpect(jsonPath("$.eventStartsAt").exists())
			.andExpect(jsonPath("$.recipients").value(1));
		assertThat(titles(adultAccount)).containsExactly("Co zabrać");
		assertThat(titles(parentAccount)).isEmpty();
	}

	@Test
	void rejectsClassesThatAreCancelledOrTooFarAhead() throws Exception {
		UUID cancelled = addEvent("Odwołane", Instant.now().plus(1, ChronoUnit.DAYS), "CANCELLED");
		UUID far = addEvent("Za miesiąc", Instant.now().plus(40, ChronoUnit.DAYS), "SCHEDULED");
		UUID past = addEvent("Wczoraj", Instant.now().minus(1, ChronoUnit.DAYS), "SCHEDULED");
		for (UUID event : List.of(cancelled, far, past)) {
			preview(Map.of("audience", "EVENT", "eventId", event))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("eventId"));
		}
		preview(Map.of("audience", "GROUP"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("trainingGroupId"));
	}

	@Test
	void personAnnouncementGoesToClientsAndTrainersWhateverRoleTheyUse() throws Exception {
		send(Map.of("audience", "PERSON", "accountId", trainerClientAccount, "title", "Grafik", "body", "Sprawdź grafik."))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.targetName").value("Karolina Nowak"));
		send(Map.of("audience", "PERSON", "accountId", trainerAccount, "title", "Zastępstwo", "body", "Masz zastępstwo."))
			.andExpect(status().isCreated());

		// Both roles of a client who is also a trainer see it, without a role in front.
		for (String role : List.of("CLIENT", "TRAINER")) {
			mvc.perform(get("/api/v1/me/notifications").param("role", role).header("Authorization", "Bearer trainer-client"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].type").value("ANNOUNCEMENT"))
				.andExpect(jsonPath("$.items[0].title").value("Grafik"))
				.andExpect(jsonPath("$.items[0].body").value("Sprawdź grafik."))
				.andExpect(jsonPath("$.items[0].data.marketing").value("false"))
				.andExpect(jsonPath("$.items[0].data.author").value("Beata Lis"));
		}
		assertThat(jdbc.sql("SELECT recipient_role FROM notification WHERE recipient_id = :id")
			.param("id", trainerAccount).query(String.class).single()).isEqualTo("TRAINER");

		send(Map.of("audience", "PERSON", "accountId", adminAccount, "title", "T", "body", "B"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("accountId"));
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :id").param("id", adultAccount).update();
		send(Map.of("audience", "PERSON", "accountId", adultAccount, "title", "T", "body", "B"))
			.andExpect(status().isBadRequest());
	}

	// --- marketing consent ---

	@Test
	void marketingAnnouncementOnlyReachesPeopleWithTheConsent() throws Exception {
		consent(adult);
		consent(trainerClient);
		revoke(trainerClient);

		String preview = preview(Map.of("audience", "ALL", "marketing", true))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		int skipped = JsonPath.read(preview, "$.skippedWithoutConsent");
		assertThat(skipped).isGreaterThanOrEqualTo(2);

		send(Map.of("audience", "ALL", "marketing", true, "title", "Promocja", "body", "Karnety 20% taniej."))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.marketing").value(true))
			.andExpect(jsonPath("$.skippedWithoutConsent").value(skipped));

		assertThat(titles(adultAccount)).containsExactly("Promocja");
		assertThat(titles(parentAccount)).isEmpty();
		assertThat(titles(trainerClientAccount)).isEmpty();
		assertThat(titles(trainerAccount)).isEmpty();
		assertThat(jdbc.sql("SELECT category FROM notification WHERE recipient_id = :id")
			.param("id", adultAccount).query(String.class).single()).isEqualTo("MARKETING");
	}

	@Test
	void announcementToAllClientsLeavesTrainersOut() throws Exception {
		send(Map.of("audience", "ALL", "title", "Nowy grafik", "body", "Od grudnia nowy grafik."))
			.andExpect(status().isCreated());

		assertThat(titles(adultAccount)).containsExactly("Nowy grafik");
		assertThat(titles(parentAccount)).containsExactly("Nowy grafik");
		assertThat(titles(trainerClientAccount)).containsExactly("Klient: Nowy grafik");
		assertThat(titles(trainerAccount)).isEmpty();
		assertThat(titles(adminAccount)).isEmpty();
	}

	@Test
	void refusesAnAnnouncementNobodyWouldGet() throws Exception {
		send(Map.of("audience", "GROUP", "trainingGroupId", group, "title", "Pusto", "body", "Nikogo nie ma."))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("ANNOUNCEMENT_NO_RECIPIENTS"));
		assertThat(jdbc.sql("SELECT count(*) FROM announcement WHERE title = 'Pusto'").query(Long.class).single()).isZero();
	}

	@Test
	void validatesTitleAndText() throws Exception {
		send(Map.of("audience", "ALL", "title", " ", "body", "x".repeat(2001)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("title", "body")));
	}

	// --- push, history, access ---

	@Test
	void pushCarriesTheAnnouncementAndHistoryCountsDeliveryAndReading() throws Exception {
		joinGroup(adult);
		joinGroup(child);
		addDevice(adultAccount, "token-adult");
		String sent = send(Map.of("audience", "GROUP", "trainingGroupId", group, "title", "Zmiana sali", "body", "Sala 2."))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(sent, "$.id");
		dispatcher.dispatch(jdbc.sql("SELECT id FROM notification WHERE announcement_id = :id")
			.param("id", UUID.fromString(id)).query(UUID.class).list());

		ArgumentCaptor<PushMessage> push = ArgumentCaptor.forClass(PushMessage.class);
		org.mockito.Mockito.verify(pushSender).send(push.capture());
		assertThat(push.getValue().title()).isEqualTo("Zmiana sali");
		assertThat(push.getValue().data()).containsEntry("type", "ANNOUNCEMENT").containsEntry("announcementId", id);

		jdbc.sql("UPDATE notification SET read_at = now() WHERE recipient_id = :id").param("id", adultAccount).update();

		mvc.perform(get("/api/v1/announcements").header("Authorization", "Bearer admin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].recipients").value(2))
			.andExpect(jsonPath("$.items[0].read").value(1))
			.andExpect(jsonPath("$.items[0].pushDelivered").value(1));
		mvc.perform(get("/api/v1/announcements/" + id).header("Authorization", "Bearer admin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.announcement.body").value("Sala 2."))
			.andExpect(jsonPath("$.recipients[0].name").value("Ala Kot"))
			.andExpect(jsonPath("$.recipients[0].status").value("READ"))
			.andExpect(jsonPath("$.recipients[1].name").value("Monika Lis"))
			.andExpect(jsonPath("$.recipients[1].about").value("Zosia"))
			.andExpect(jsonPath("$.recipients[1].status").value("APP_ONLY"));
		mvc.perform(get("/api/v1/announcements/" + UUID.randomUUID()).header("Authorization", "Bearer admin"))
			.andExpect(status().isNotFound());
	}

	@Test
	void onlyAdministratorsSendAnnouncements() throws Exception {
		mvc.perform(post("/api/v1/announcements").header("Authorization", "Bearer trainer")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"audience\":\"ALL\",\"title\":\"T\",\"body\":\"B\"}"))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/announcements").header("Authorization", "Bearer adult"))
			.andExpect(status().isForbidden());
	}

	@Test
	void announcementsAreNotAConfigurableNotificationType() throws Exception {
		mvc.perform(get("/api/v1/notification-types").header("Authorization", "Bearer admin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].code").value(not(hasItem("ANNOUNCEMENT"))));
		mvc.perform(get("/api/v1/notification-types/ANNOUNCEMENT").header("Authorization", "Bearer admin"))
			.andExpect(status().isNotFound());
	}

	// --- helpers ---

	private ResultActions preview(Map<String, Object> body) throws Exception {
		return mvc.perform(post("/api/v1/announcements/preview").header("Authorization", "Bearer admin")
			.contentType(MediaType.APPLICATION_JSON).content(json(body)));
	}

	private ResultActions send(Map<String, Object> body) throws Exception {
		return mvc.perform(post("/api/v1/announcements").header("Authorization", "Bearer admin")
			.contentType(MediaType.APPLICATION_JSON).content(json(body)));
	}

	private static String json(Map<String, Object> body) {
		StringBuilder out = new StringBuilder("{");
		body.forEach((key, value) -> {
			if (out.length() > 1) {
				out.append(',');
			}
			out.append('"').append(key).append("\":");
			out.append(value instanceof Boolean ? value.toString()
					: '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"');
		});
		return out.append('}').toString();
	}

	private List<String> titles(UUID account) {
		return jdbc.sql("SELECT title FROM notification WHERE recipient_id = :id AND notification_type_id = 14 ORDER BY title")
			.param("id", account).query(String.class).list();
	}

	private void joinGroup(UUID profile) {
		jdbc.sql("INSERT INTO group_membership (group_id, customer_id, joined_on) VALUES (:group, :profile, :day)")
			.param("group", group).param("profile", profile).param("day", LocalDate.now().minusDays(10)).update();
	}

	private void consent(UUID profile) {
		jdbc.sql("""
				INSERT INTO document_acceptance (document_version_id, customer_id, accepted_by)
				SELECT v.id, :profile, :admin FROM document_version v JOIN document d ON d.id = v.document_id
				WHERE d.code = 'MARKETING_PUSH' AND v.version_no = 1
				""").param("profile", profile).param("admin", adminAccount).update();
	}

	private void revoke(UUID profile) {
		jdbc.sql("UPDATE document_acceptance SET revoked_at = now() WHERE customer_id = :profile")
			.param("profile", profile).update();
	}

	private void addDevice(UUID account, String token) {
		jdbc.sql("INSERT INTO device_token (account_id, token, platform) VALUES (:account, :token, 'ANDROID')")
			.param("account", account).param("token", token).update();
	}

	private UUID addEvent(String title, Instant startsAt, String status) {
		return jdbc.sql("""
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
	}

	private void reserve(UUID event, UUID customer) {
		jdbc.sql("INSERT INTO reservation (event_id, customer_id, booked_by, status) VALUES (:event, :customer, :by, 'ACTIVE')")
			.param("event", event).param("customer", customer).param("by", adultAccount).update();
	}

	private UUID addAccount(String name, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@announcements.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", "announcements-" + name).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
				.param("id", id).param("code", role).update();
		}
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256")
			.subject("announcements-" + name).build());
		return id;
	}

	private UUID addProfile(UUID account, String kind, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, :firstName, :lastName) RETURNING id
				""").param("account", account, java.sql.Types.OTHER).param("kind", kind).param("firstName", firstName)
			.param("lastName", lastName).query(UUID.class).single();
	}

}
