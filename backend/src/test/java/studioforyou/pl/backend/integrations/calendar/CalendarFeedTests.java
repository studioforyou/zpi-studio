package studioforyou.pl.backend.integrations.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.TestcontainersConfiguration;

/** Private calendar feed links (#121) and the feed they serve, against the real migrated schema. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.calendar-feeds.base-url=https://api.example.com/")
@Transactional
class CalendarFeedTests {

	private static final String ME = "/api/v1/me/calendar-feed";

	private static final String EVENTS = "/api/v1/me/calendar-events";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private final Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	private UUID service;

	private UUID parent;

	private UUID parentProfile;

	private UUID child;

	@BeforeEach
	void setUp() {
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes)
				VALUES ('Pole dance, beginners', 'GROUP', 60) RETURNING id
				""").query(UUID.class).single();
		parent = addAccount("parent", "CLIENT");
		parentProfile = addProfile(parent, "ADULT", "Monika");
		child = addProfile(null, "CHILD", "Zosia");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", parentProfile).param("child", child).update();
	}

	@Test
	void createsChecksAndTurnsOffTheLink() throws Exception {
		mvc.perform(get(ME).header("Authorization", "Bearer parent"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false))
			.andExpect(jsonPath("$.createdAt").doesNotExist());

		String token = create("parent");
		assertThat(token).hasSize(43);
		String hash = jdbc.sql("SELECT token_hash FROM calendar_feed WHERE account_id = :id").param("id", parent)
			.query(String.class).single();
		assertThat(hash).isEqualTo(CalendarFeedTokens.hash(token)).doesNotContain(token);
		mvc.perform(get(ME).header("Authorization", "Bearer parent"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.createdAt").isString());
		feed(token, "").andExpect(status().isOk());

		mvc.perform(delete(ME).header("Authorization", "Bearer parent")).andExpect(status().isNoContent());
		mvc.perform(get(ME).header("Authorization", "Bearer parent")).andExpect(jsonPath("$.active").value(false));
		feed(token, "").andExpect(status().isNotFound());
		mvc.perform(delete(ME).header("Authorization", "Bearer parent")).andExpect(status().isNoContent());
	}

	@Test
	void aNewLinkReplacesThePreviousOne() throws Exception {
		String first = create("parent");
		String second = create("parent");

		assertThat(second).isNotEqualTo(first);
		feed(first, "").andExpect(status().isNotFound());
		feed(second, "").andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT count(*) FROM calendar_feed WHERE account_id = :id").param("id", parent)
			.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void returnsHttpsAndWebcalAddressesBuiltFromTheConfiguredBaseUrl() throws Exception {
		mvc.perform(post(ME).header("Authorization", "Bearer parent"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", ME))
			.andExpect(jsonPath("$.url").value(org.hamcrest.Matchers
				.matchesPattern("https://api\\.example\\.com/api/v1/calendar-feeds/[A-Za-z0-9_-]{43}/events\\.ics")))
			.andExpect(jsonPath("$.webcalUrl").value(org.hamcrest.Matchers
				.matchesPattern("webcal://api\\.example\\.com/api/v1/calendar-feeds/[A-Za-z0-9_-]{43}/events\\.ics")));
	}

	@Test
	void theClientFeedHasOwnAndChildReservationsAndStudioCancellations() throws Exception {
		UUID own = addEvent("CLASS", null, start, "SCHEDULED");
		UUID childClass = addEvent("CLASS", "Kids acrobatics", start.plus(Duration.ofDays(1)), "SCHEDULED");
		UUID cancelledByStudio = addEvent("CLASS", null, start.plus(Duration.ofDays(2)), "CANCELLED");
		UUID cancelledByClient = addEvent("CLASS", "Left out", start.plus(Duration.ofDays(3)), "SCHEDULED");
		UUID longAgo = addEvent("CLASS", "Long ago", start.minus(Duration.ofDays(40)), "COMPLETED");
		UUID recent = addEvent("CLASS", "Last week", start.minus(Duration.ofDays(9)), "COMPLETED");
		UUID someoneElses = addEvent("CLASS", "Someone else's", start, "SCHEDULED");
		addReservation(own, parentProfile, "ACTIVE", null);
		addReservation(childClass, child, "ACTIVE", null);
		addReservation(cancelledByStudio, parentProfile, "CANCELLED", "STUDIO");
		addReservation(cancelledByClient, parentProfile, "CANCELLED", "IN_TIME");
		addReservation(longAgo, parentProfile, "ACTIVE", null);
		addReservation(recent, parentProfile, "ACTIVE", null);
		addReservation(someoneElses, addProfile(addAccount("other", "CLIENT"), "ADULT", "Other"), "ACTIVE", null);
		addRoom(own, "Pole room");

		String calendar = body(feed(create("parent"), ""));

		assertThat(calendar).contains("X-WR-CALNAME:Studio – mój grafik", "UID:" + own + "-" + parentProfile,
				"SUMMARY:Pole dance\\, beginners", "LOCATION:Pole room", "SUMMARY:Zosia: Kids acrobatics",
				"UID:" + cancelledByStudio + "-" + parentProfile, "SUMMARY:Last week")
			.doesNotContain("Left out", "Long ago", "Someone else's", "Klient ·");
		assertThat(event(calendar, cancelledByStudio)).contains("STATUS:CANCELLED");
		assertThat(event(calendar, own)).contains("STATUS:CONFIRMED",
				"DTSTART:" + ical(start), "DTEND:" + ical(start.plus(Duration.ofHours(1))));
	}

	@Test
	void aTrainerSeesTheEventsTheyRunAndBothRolesAreLabelledInOneCalendar() throws Exception {
		UUID trainer = addAccount("trainer", "TRAINER", "CLIENT");
		UUID staff = jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:account, 'Anna', 'Kowalska')
				RETURNING id
				""").param("account", trainer).query(UUID.class).single();
		UUID runs = addEvent("CLASS", null, start, "SCHEDULED");
		UUID personal = addEvent("PERSONAL_TRAINING", null, start.plus(Duration.ofDays(1)), "CANCELLED");
		UUID block = addEvent("BLOCK", "Room cleaning", start.plus(Duration.ofDays(2)), "SCHEDULED");
		UUID notTheirs = addEvent("CLASS", "Another trainer's class", start, "SCHEDULED");
		for (UUID event : new UUID[] { runs, personal, block }) {
			jdbc.sql("INSERT INTO event_staff (event_id, staff_id) VALUES (:event, :staff)")
				.param("event", event).param("staff", staff).update();
		}
		UUID booked = addEvent("WORKSHOP", "Stretching workshop", start.plus(Duration.ofDays(3)), "SCHEDULED");
		addReservation(booked, addProfile(trainer, "ADULT", "Anna"), "ACTIVE", null);
		String token = create("trainer");

		String both = body(feed(token, ""));
		assertThat(both).contains("SUMMARY:Trener · Pole dance\\, beginners", "DESCRIPTION:Prowadzi: Anna Kowalska",
				"SUMMARY:Trener · Trening personalny", "SUMMARY:Klient · Stretching workshop")
			.doesNotContain("Room cleaning", "Another trainer's class");
		assertThat(event(both, personal)).contains("STATUS:CANCELLED");

		String trainerOnly = body(feed(token, "?role=TRAINER"));
		assertThat(trainerOnly).contains("X-WR-CALNAME:Studio – prowadzone zajęcia", "SUMMARY:Pole dance\\, beginners")
			.doesNotContain("Stretching workshop", "Trener ·");

		String clientOnly = body(feed(token, "?role=CLIENT"));
		assertThat(clientOnly).contains("SUMMARY:Stretching workshop").doesNotContain("Pole dance");

		feed(token, "?role=ADMIN").andExpect(status().isBadRequest());
	}

	@Test
	void aRoleTheAccountDoesNotHoldGivesAnEmptyCalendar() throws Exception {
		addReservation(addEvent("CLASS", null, start, "SCHEDULED"), parentProfile, "ACTIVE", null);

		assertThat(body(feed(create("parent"), "?role=TRAINER"))).doesNotContain("BEGIN:VEVENT");
	}

	@Test
	void unknownTokensAndInactiveAccountsGetNotFound() throws Exception {
		feed("unknown-token", "").andExpect(status().isNotFound());
		// Calendars ask for text/calendar; the error still has its status.
		mvc.perform(get(CalendarFeedService.FEED_PATH + "unknown-token" + CalendarFeedService.FEED_FILE)
			.header("Accept", "text/calendar")).andExpect(status().isNotFound());
		String token = create("parent");
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :id").param("id", parent).update();
		feed(token, "").andExpect(status().isNotFound());
	}

	@Test
	void theFeedIsPublicButTheLinkNeedsAClientOrTrainer() throws Exception {
		mvc.perform(get(ME)).andExpect(status().isUnauthorized());
		mvc.perform(post(ME)).andExpect(status().isUnauthorized());
		addAccount("admin", "ADMIN");
		mvc.perform(post(ME).header("Authorization", "Bearer admin")).andExpect(status().isForbidden());
		String token = create("parent");
		mvc.perform(get(CalendarFeedService.FEED_PATH + token + CalendarFeedService.FEED_FILE)
			.header("Accept", "text/calendar"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/calendar"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
	}

	@Test
	void theAppGetsTheSameEntriesAsJsonWithoutALink() throws Exception {
		UUID own = addEvent("CLASS", null, start, "SCHEDULED");
		UUID cancelledByStudio = addEvent("CLASS", null, start.plus(Duration.ofDays(2)), "CANCELLED");
		UUID childClass = addEvent("CLASS", "Kids acrobatics", start.plus(Duration.ofDays(1)), "SCHEDULED");
		addReservation(own, parentProfile, "ACTIVE", null);
		addReservation(childClass, child, "ACTIVE", null);
		addReservation(cancelledByStudio, parentProfile, "CANCELLED", "STUDIO");
		addRoom(own, "Pole room");

		mvc.perform(get(EVENTS).header("Authorization", "Bearer parent"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].uid").value(own + "-" + parentProfile + "@studioforyou.pl"))
			.andExpect(jsonPath("$.items[0].title").value("Pole dance, beginners"))
			.andExpect(jsonPath("$.items[0].location").value("Pole room"))
			.andExpect(jsonPath("$.items[0].startsAt").value(start.toString()))
			.andExpect(jsonPath("$.items[0].cancelled").value(false))
			.andExpect(jsonPath("$.items[1].title").value("Zosia: Kids acrobatics"))
			.andExpect(jsonPath("$.items[2].cancelled").value(true));
		mvc.perform(get(EVENTS).param("role", "TRAINER").header("Authorization", "Bearer parent"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get(EVENTS)).andExpect(status().isUnauthorized());
	}

	private String create(String account) throws Exception {
		String response = mvc.perform(post(ME).header("Authorization", "Bearer " + account))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		String url = JsonPath.read(response, "$.url");
		return url.substring(url.indexOf(CalendarFeedService.FEED_PATH) + CalendarFeedService.FEED_PATH.length(),
				url.length() - CalendarFeedService.FEED_FILE.length());
	}

	private org.springframework.test.web.servlet.ResultActions feed(String token, String query) throws Exception {
		return mvc.perform(get(CalendarFeedService.FEED_PATH + token + CalendarFeedService.FEED_FILE + query));
	}

	/** The calendar with its folded lines joined again. */
	private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
			.replace("\r\n ", "");
	}

	/** The VEVENT of the event. */
	private static String event(String calendar, UUID eventId) {
		int uid = calendar.indexOf("UID:" + eventId);
		assertThat(uid).isNotNegative();
		return calendar.substring(calendar.lastIndexOf("BEGIN:VEVENT", uid), calendar.indexOf("END:VEVENT", uid));
	}

	private static String ical(Instant instant) {
		return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
			.format(instant);
	}

	private UUID addAccount(String name, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@calendar.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", "calendar-" + name).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
				.param("id", id).param("role", role).update();
		}
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256")
			.subject("calendar-" + name).build());
		return id;
	}

	private UUID addProfile(UUID accountId, String kind, String firstName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, :firstName, 'Tester') RETURNING id
				""").param("account", accountId, java.sql.Types.OTHER).param("kind", kind)
			.param("firstName", firstName).query(UUID.class).single();
	}

	private UUID addEvent(String kind, String title, Instant startsAt, String status) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, status)
				VALUES (:kind, :service, :title, :start, :end, 8, :status) RETURNING id
				""").param("kind", kind).param("service", "PERSONAL_TRAINING".equals(kind) ? null : service,
						java.sql.Types.OTHER)
			.param("title", title, java.sql.Types.VARCHAR)
			.param("start", startsAt.atOffset(ZoneOffset.UTC))
			.param("end", startsAt.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
			.param("status", status).query(UUID.class).single();
	}

	private void addReservation(UUID event, UUID profile, String status, String cancelKind) {
		jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status, cancel_kind)
				VALUES (:event, :profile, :bookedBy, :status, :cancelKind)
				""").param("event", event).param("profile", profile).param("bookedBy", parent)
			.param("status", status).param("cancelKind", cancelKind, java.sql.Types.VARCHAR).update();
	}

	private void addRoom(UUID event, String name) {
		UUID resource = jdbc.sql("INSERT INTO resource (name) VALUES (:name) RETURNING id")
			.param("name", name).query(UUID.class).single();
		jdbc.sql("INSERT INTO event_resource (event_id, resource_id) VALUES (:event, :resource)")
			.param("event", event).param("resource", resource).update();
	}

}
