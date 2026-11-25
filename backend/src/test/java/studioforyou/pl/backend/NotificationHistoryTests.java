package studioforyou.pl.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.BDDMockito.given;

/**
 * The administrator's history of sent notifications (#161): every type, the status from the
 * delivery attempts, filters and the hidden chat contents, against real PostgreSQL. The test rows
 * lie in 2001 so the window filter keeps out anything else in the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class NotificationHistoryTests {

	private static final Instant DAY = Instant.parse("2001-03-10T00:00:00Z");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID client;

	private UUID parent;

	private UUID child;

	private UUID read;

	private UUID delivered;

	private UUID failed;

	private UUID appOnly;

	private UUID chat;

	private UUID announcement;

	@BeforeEach
	void setUp() {
		UUID admin = addAccount("admin", "ADMIN");
		addProfile(admin, "ADULT", "Beata", "Lis");
		addAccount("trainer", "TRAINER");
		client = addAccount("client", "CLIENT");
		addProfile(client, "ADULT", "Ala", "Kot");
		parent = addAccount("parent", "CLIENT");
		addProfile(parent, "ADULT", "Monika", "Lis");
		child = addProfile(null, "CHILD", "Zosia", "Lis");

		read = addNotification(client, "CLASS_REMINDER", "Jutro: Pole dance", "czw 18:00", 10, null, "{}");
		jdbc.sql("UPDATE notification SET read_at = :at WHERE id = :id")
			.param("at", at(11).atOffset(ZoneOffset.UTC)).param("id", read).update();
		delivery(read, "SENT", null);
		delivered = addNotification(parent, "CLASS_CANCELLED", "Zajęcia odwołane", "Joga", 9, child, "{}");
		delivery(delivered, "FAILED", "UNAVAILABLE");
		delivery(delivered, "SENT", null);
		failed = addNotification(client, "PASS_EXPIRING", "Karnet wygasa", "za 3 dni", 8, null, "{}");
		delivery(failed, "TOKEN_INVALID", "UNREGISTERED");
		appOnly = addNotification(parent, "PASS_EXPIRING", "Karnet wygasa", "za 2 dni", 7, null, "{}");
		delivery(appOnly, "NO_DEVICE", null);
		chat = addNotification(client, "NEW_MESSAGE", "Anna Nowak", "Prywatna treść rozmowy", 6, null, "{}");
		announcement = addNotification(parent, "ANNOUNCEMENT", "Zosia: Zmiana sali", "Sala 2.", 5, null,
				"{\"author\":\"Beata Lis\",\"about\":\"Zosia\"}");
		jdbc.sql("UPDATE notification SET category = 'MARKETING' WHERE id = :id").param("id", announcement).update();
	}

	@Test
	void listsSentNotificationsNewestFirstWithStatusAndHiddenChatText() throws Exception {
		addNotification(client, "CLASS_REMINDER", "Not yet", null, 0, null, "{}");
		jdbc.sql("UPDATE notification SET scheduled_for = now() + interval '1 day' WHERE title = 'Not yet'").update();

		mvc.perform(admin(get("/api/v1/notification-history")).param("from", DAY.toString())
				.param("to", DAY.plus(1, ChronoUnit.DAYS).toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(6))
			.andExpect(jsonPath("$.items[0].id").value(read.toString()))
			.andExpect(jsonPath("$.items[0].type").value("CLASS_REMINDER"))
			.andExpect(jsonPath("$.items[0].recipientName").value("Ala Kot"))
			.andExpect(jsonPath("$.items[0].status").value("READ"))
			.andExpect(jsonPath("$.items[0].readAt").exists())
			.andExpect(jsonPath("$.items[1].status").value("DELIVERED"))
			.andExpect(jsonPath("$.items[1].about").value("Zosia Lis"))
			.andExpect(jsonPath("$.items[2].status").value("PUSH_FAILED"))
			.andExpect(jsonPath("$.items[3].status").value("APP_ONLY"))
			.andExpect(jsonPath("$.items[4].type").value("NEW_MESSAGE"))
			.andExpect(jsonPath("$.items[4].title").value("Anna Nowak"))
			.andExpect(jsonPath("$.items[4].body").doesNotExist())
			.andExpect(jsonPath("$.items[4].bodyHidden").value(true))
			.andExpect(jsonPath("$.items[4].status").value("APP_ONLY"))
			.andExpect(jsonPath("$.items[5].type").value("ANNOUNCEMENT"))
			.andExpect(jsonPath("$.items[5].author").value("Beata Lis"))
			.andExpect(jsonPath("$.items[5].about").value("Zosia"))
			.andExpect(jsonPath("$.items[5].marketing").value(true))
			.andExpect(jsonPath("$.items[5].body").value("Sala 2."));
	}

	@Test
	void filtersCombine() throws Exception {
		mvc.perform(window().param("type", "PASS_EXPIRING"))
			.andExpect(jsonPath("$.totalItems").value(2));
		mvc.perform(window().param("type", "PASS_EXPIRING").param("accountId", parent.toString()))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].id").value(appOnly.toString()));
		mvc.perform(window().param("accountId", client.toString()).param("status", "PUSH_FAILED"))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].id").value(failed.toString()));
		mvc.perform(window().param("status", "DELIVERED"))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].id").value(delivered.toString()));
		mvc.perform(admin(get("/api/v1/notification-history")).param("from", at(8).toString())
				.param("to", at(10).toString()))
			.andExpect(jsonPath("$.totalItems").value(2));
		mvc.perform(window().param("size", "2").param("page", "1"))
			.andExpect(jsonPath("$.totalItems").value(6))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].id").value(failed.toString()));
	}

	@Test
	void rejectsUnknownTypesAndBackwardPeriods() throws Exception {
		mvc.perform(admin(get("/api/v1/notification-history")).param("type", "NOPE"))
			.andExpect(status().isBadRequest());
		mvc.perform(admin(get("/api/v1/notification-history")).param("from", at(10).toString())
				.param("to", at(9).toString()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void detailsListEveryDeliveryAttempt() throws Exception {
		mvc.perform(admin(get("/api/v1/notification-history/" + delivered)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.notification.title").value("Zajęcia odwołane"))
			.andExpect(jsonPath("$.notification.recipientName").value("Monika Lis"))
			.andExpect(jsonPath("$.deliveries.length()").value(2))
			.andExpect(jsonPath("$.deliveries[0].status").value("FAILED"))
			.andExpect(jsonPath("$.deliveries[0].error").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.deliveries[1].status").value("SENT"));
		mvc.perform(admin(get("/api/v1/notification-history/" + chat)))
			.andExpect(jsonPath("$.notification.body").doesNotExist())
			.andExpect(jsonPath("$.notification.bodyHidden").value(true));
		mvc.perform(admin(get("/api/v1/notification-history/" + UUID.randomUUID())))
			.andExpect(status().isNotFound());
	}

	@Test
	void onlyAdministratorsSeeTheHistory() throws Exception {
		mvc.perform(get("/api/v1/notification-history").header("Authorization", "Bearer trainer"))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/notification-history/" + read).header("Authorization", "Bearer client"))
			.andExpect(status().isForbidden());
	}

	private MockHttpServletRequestBuilder window() {
		return admin(get("/api/v1/notification-history")).param("from", DAY.toString())
			.param("to", DAY.plus(1, ChronoUnit.DAYS).toString());
	}

	private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer admin");
	}

	private static Instant at(int hour) {
		return DAY.plus(hour, ChronoUnit.HOURS);
	}

	private UUID addNotification(UUID recipient, String type, String title, String body, int hour, UUID subject,
			String payload) {
		return jdbc.sql("""
				INSERT INTO notification (recipient_id, notification_type_id, recipient_role, subject_customer_id,
				  title, body, payload, scheduled_for, created_at)
				SELECT :recipient, t.id, 'CLIENT', :subject, :title, :body, CAST(:payload AS JSONB), :at, :at
				FROM notification_type t WHERE t.code = :type
				RETURNING id
				""")
			.param("recipient", recipient)
			.param("subject", subject, java.sql.Types.OTHER)
			.param("title", title)
			.param("body", body, java.sql.Types.VARCHAR)
			.param("payload", payload)
			.param("at", at(hour).atOffset(ZoneOffset.UTC))
			.param("type", type)
			.query(UUID.class)
			.single();
	}

	private void delivery(UUID notification, String status, String error) {
		jdbc.sql("""
				INSERT INTO notification_delivery (notification_id, channel, status, error, attempted_at)
				VALUES (:id, 'PUSH', :status, :error, clock_timestamp())
				""")
			.param("id", notification).param("status", status).param("error", error, java.sql.Types.VARCHAR).update();
	}

	private UUID addAccount(String name, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@history.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", "history-" + name).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
				.param("id", id).param("code", role).update();
		}
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256")
			.subject("history-" + name).build());
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
