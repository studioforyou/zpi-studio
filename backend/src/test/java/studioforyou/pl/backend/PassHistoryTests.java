package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrators read the change history of a participant's pass (#85): the staff's own changes
 * made through the API, and bookings, make-up credits and renewal decisions recorded by the other
 * workflows. Each test runs in a transaction that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional(isolation = Isolation.REPEATABLE_READ)
class PassHistoryTests {

	private static final ZoneId STUDIO_ZONE = ZoneId.of("Europe/Warsaw");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID admin;

	private UUID client;

	private UUID adult;

	private UUID variant;

	private Instant now;

	@BeforeEach
	void setUp() {
		now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		admin = account("admin", "ADMIN");
		jdbc.sql("INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:id, 'Beata', 'Lis')")
			.param("id", admin)
			.update();
		client = account("client", "CLIENT");
		adult = profile(client, "ADULT", "Katarzyna", "Zielińska");
		UUID product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience, suspension_allowed)
				VALUES ('Karnet Pole Dance', 'ENTRY_PASS', 'ANY', TRUE) RETURNING id
				""")
			.query(UUID.class)
			.single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 260) RETURNING id
				""")
			.param("product", product)
			.query(UUID.class)
			.single();
	}

	@Test
	void showsTheStaffsChangesNewestFirstWithAuthorsNotesAndBalances() throws Exception {
		String assigned = perform("admin", post(passesOf(adult)), """
				{ "variantId": "%s", "validFrom": "%s", "reason": "Płatność gotówką" }
				""".formatted(variant, today()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID pass = UUID.fromString(JsonPath.read(assigned, "$.id"));
		perform("admin", post(passPath(adult, pass) + "/correct-entries"),
				"{ \"entriesDelta\": -2, \"reason\": \"Wejścia sprzed aplikacji\", \"version\": 0 }")
			.andExpect(status().isOk());
		String suspended = perform("admin", post(passPath(adult, pass) + "/suspend"), """
				{ "startsOn": "%s", "endsOn": "%s", "reason": "Wyjazd", "version": 1 }
				""".formatted(today().plusDays(3), today().plusDays(5)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		perform("admin", post(passPath(adult, pass) + "/extend"), """
				{ "validUntil": "%s", "reason": "Kontuzja", "version": 2 }
				""".formatted(today().plusDays(42)))
			.andExpect(status().isOk());
		String suspension = JsonPath.read(suspended, "$.suspensions[0].id");
		perform("admin", post(passPath(adult, pass) + "/suspensions/" + suspension + "/cancel"), "{ \"version\": 3 }")
			.andExpect(status().isOk());

		String body = history(adult, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.pass.productName").value("Karnet Pole Dance"))
			.andExpect(jsonPath("$.pass.entriesTotal").value(8))
			.andExpect(jsonPath("$.pass.entriesRemaining").value(6))
			.andExpect(jsonPath("$.pass.ledgerBalance").value(6))
			.andExpect(jsonPath("$.pass.purchased").value(false))
			.andExpect(jsonPath("$.counts.PASS").value(1))
			.andExpect(jsonPath("$.counts.CORRECTIONS").value(1))
			.andExpect(jsonPath("$.counts.VALIDITY").value(3))
			.andExpect(jsonPath("$.counts.ENTRIES").value(0))
			.andExpect(jsonPath("$.counts.MAKEUP").value(0))
			.andExpect(jsonPath("$.entries.totalItems").value(5))
			.andExpect(jsonPath("$.entries.items[*].kind", contains("SUSPENSION_CANCELLED", "EXTENDED",
					"SUSPENDED", "CORRECTED", "ACTIVATED")))
			.andReturn()
			.getResponse()
			.getContentAsString();

		Map<String, Object> cancelled = item(body, 0);
		assertThat(cancelled).containsEntry("category", "VALIDITY")
			.containsEntry("reason", null)
			.containsEntry("entriesChange", null)
			.containsEntry("validUntilBefore", today().plusDays(42).toString())
			.containsEntry("validUntilAfter", today().plusDays(39).toString())
			.containsEntry("suspension", Map.of("startsOn", today().plusDays(3).toString(), "endsOn",
					today().plusDays(5).toString()))
			.containsEntry("actor", Map.of("name", "Beata Lis", "role", "STAFF"));
		assertThat(item(body, 1)).containsEntry("reason", "Kontuzja")
			.containsEntry("validUntilBefore", today().plusDays(32).toString())
			.containsEntry("validUntilAfter", today().plusDays(42).toString())
			.containsEntry("suspension", null);
		assertThat(item(body, 2)).containsEntry("reason", "Wyjazd")
			.containsEntry("validUntilBefore", today().plusDays(29).toString())
			.containsEntry("validUntilAfter", today().plusDays(32).toString())
			.containsEntry("suspension", Map.of("startsOn", today().plusDays(3).toString(), "endsOn",
					today().plusDays(5).toString()));
		assertThat(item(body, 3)).containsEntry("category", "CORRECTIONS")
			.containsEntry("reason", "Wejścia sprzed aplikacji")
			.containsEntry("entriesChange", -2)
			.containsEntry("balanceAfter", 6);
		assertThat(item(body, 4)).containsEntry("category", "PASS")
			.containsEntry("reason", "Płatność gotówką")
			.containsEntry("entriesChange", 8)
			.containsEntry("balanceAfter", 8)
			.containsEntry("validUntilAfter", today().plusDays(29).toString())
			.containsEntry("actor", Map.of("name", "Beata Lis", "role", "STAFF"));

		history(adult, pass, "?category=VALIDITY&size=2&page=1").andExpect(status().isOk())
			.andExpect(jsonPath("$.counts.VALIDITY").value(3))
			.andExpect(jsonPath("$.entries.totalItems").value(3))
			.andExpect(jsonPath("$.entries.totalPages").value(2))
			.andExpect(jsonPath("$.entries.items", hasSize(1)))
			.andExpect(jsonPath("$.entries.items[0].kind").value("SUSPENDED"));
	}

	@Test
	void describesTheClassesItsEntriesWereForAndWhoBookedCancelledAndMarkedThem() throws Exception {
		UUID pass = pass(adult, 8, 5);
		ledger(pass, null, "ACTIVATION", 8, admin, "Opłacone zamówienie", -100);
		UUID cancelled = reservation(adult, pass, "Pole dance — podstawy", client, "SELF", -48);
		jdbc.sql("""
				UPDATE reservation SET status = 'CANCELLED', cancel_kind = 'IN_TIME', cancelled_by = :client,
				       cancelled_at = :at WHERE id = :id
				""")
			.param("client", client)
			.param("at", at(-60))
			.param("id", cancelled)
			.update();
		ledger(pass, cancelled, "ENTRY_RESERVED", -1, client, null, -90);
		ledger(pass, cancelled, "ENTRY_RETURNED", 1, client, null, -60);
		UUID attended = reservation(adult, pass, "Pole dance — technika", admin, "STAFF", -30);
		jdbc.sql("UPDATE reservation SET attendance = 'PRESENT' WHERE id = :id").param("id", attended).update();
		ledger(pass, attended, "ENTRY_RESERVED", -1, admin, null, -50);
		ledger(pass, attended, "ENTRY_CONSUMED", 0, admin, null, -29);
		UUID upcoming = reservation(adult, pass, "Pole dance — kombinacje", client, "SELF", 24);
		ledger(pass, upcoming, "ENTRY_RESERVED", -1, client, null, -20);
		UUID makeup = reservation(adult, null, "Stretching — odrabianie", client, "SELF", 48);
		jdbc.sql("""
				INSERT INTO makeup_credit (customer_id, source_pass_id, source_reservation_id, granted_by, granted_at,
				                           expires_at, status, used_reservation_id, used_at)
				VALUES (:customer, :pass, :source, NULL, :granted, :expires, 'USED', :used, :usedAt)
				""")
			.param("customer", adult)
			.param("pass", pass)
			.param("source", cancelled)
			.param("granted", at(-59))
			.param("expires", at(24 * 20))
			.param("used", makeup)
			.param("usedAt", at(-10))
			.update();
		jdbc.sql("""
				INSERT INTO makeup_credit (customer_id, source_pass_id, granted_by, granted_at, expires_at)
				VALUES (:customer, :pass, :admin, :granted, :expires)
				""")
			.param("customer", adult)
			.param("pass", pass)
			.param("admin", admin)
			.param("granted", at(-80))
			.param("expires", at(-70))
			.update();
		jdbc.sql("UPDATE customer_pass SET renewal_declined_at = :at WHERE id = :id")
			.param("at", at(-5))
			.param("id", pass)
			.update();

		String body = history(adult, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.items[*].kind", contains("RENEWAL_DECLINED", "ENTRY_RESERVED",
					"ENTRY_USED", "ENTRY_RESERVED", "MAKEUP_GRANTED", "ENTRY_RETURNED", "MAKEUP_GRANTED",
					"ENTRY_RESERVED", "ACTIVATED")))
			.andExpect(jsonPath("$.counts.ENTRIES").value(5))
			.andExpect(jsonPath("$.counts.MAKEUP").value(2))
			.andExpect(jsonPath("$.counts.PASS").value(2))
			// The stored balance went down once more than the history shows, e.g. before it was kept.
			.andExpect(jsonPath("$.pass.entriesRemaining").value(5))
			.andExpect(jsonPath("$.pass.ledgerBalance").value(6))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(item(body, 0)).containsEntry("category", "PASS")
			.containsEntry("actor", null)
			.containsEntry("session", null);
		assertThat(item(body, 1)).containsEntry("entriesChange", -1).containsEntry("balanceAfter", 6);
		assertThat(session(body, 1)).containsEntry("reservationId", upcoming.toString())
			.containsEntry("title", "Pole dance — kombinacje")
			.containsEntry("status", "ACTIVE")
			.containsEntry("source", "SELF")
			.containsEntry("attendance", "UNMARKED")
			.containsEntry("bookedBy", Map.of("name", "Katarzyna Zielińska", "role", "CLIENT"));
		assertThat(item(body, 2)).containsEntry("category", "ENTRIES")
			.containsEntry("entriesChange", 0)
			.containsEntry("balanceAfter", 7)
			.containsEntry("actor", Map.of("name", "Beata Lis", "role", "STAFF"));
		assertThat(session(body, 2)).containsEntry("reservationId", attended.toString())
			.containsEntry("source", "STAFF")
			.containsEntry("attendance", "PRESENT")
			.containsEntry("bookedBy", Map.of("name", "Beata Lis", "role", "STAFF"));
		assertThat(item(body, 3)).containsEntry("entriesChange", -1).containsEntry("balanceAfter", 7);
		assertThat(item(body, 4)).containsEntry("category", "MAKEUP")
			.containsEntry("actor", null)
			.containsEntry("entriesChange", null);
		assertThat(session(body, 4)).containsEntry("reservationId", cancelled.toString());
		assertThat(JsonPath.<String>read(body, "$.entries.items[4].makeupCredit.state")).isEqualTo("USED");
		assertThat(JsonPath.<String>read(body, "$.entries.items[4].makeupCredit.usedFor.title"))
			.isEqualTo("Stretching — odrabianie");
		assertThat(item(body, 5)).containsEntry("entriesChange", 1).containsEntry("balanceAfter", 8);
		assertThat(session(body, 5)).containsEntry("status", "CANCELLED")
			.containsEntry("cancelKind", "IN_TIME")
			.containsEntry("cancelledBy", Map.of("name", "Katarzyna Zielińska", "role", "CLIENT"));
		assertThat(item(body, 6)).containsEntry("actor", Map.of("name", "Beata Lis", "role", "STAFF"))
			.containsEntry("session", null);
		assertThat(JsonPath.<String>read(body, "$.entries.items[6].makeupCredit.state")).isEqualTo("EXPIRED");
		assertThat(JsonPath.<Object>read(body, "$.entries.items[6].makeupCredit.usedFor")).isNull();

		history(adult, pass, "?category=MAKEUP").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.items[*].kind", contains("MAKEUP_GRANTED", "MAKEUP_GRANTED")));
	}

	@Test
	void aRefundAfterAnUpheldDisputeIsShownWithItsSession() throws Exception {
		UUID pass = pass(adult, 5, 5);
		ledger(pass, null, "ACTIVATION", 5, admin, null, -100);
		UUID session = reservation(adult, pass, "Trening personalny", admin, "STAFF", -30);
		ledger(pass, session, "ENTRY_RESERVED", -1, admin, null, -50);
		ledger(pass, session, "ENTRY_CONSUMED", 0, admin, null, -29);
		ledger(pass, session, "CORRECTION", 1, admin, "Trening trwał 30 minut", -2);

		String body = history(adult, pass, "?category=CORRECTIONS").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.items[*].kind", contains("DISPUTE_REFUND")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(item(body, 0)).containsEntry("reason", "Trening trwał 30 minut")
			.containsEntry("entriesChange", 1)
			.containsEntry("balanceAfter", 5);
		assertThat(session(body, 0)).containsEntry("title", "Trening personalny");
	}

	@Test
	void aGuardianIsShownAsTheAuthorOfAChildsBooking() throws Exception {
		UUID guardianAccount = account("guardian", "CLIENT");
		UUID guardian = profile(guardianAccount, "ADULT", "Monika", "Dąbrowska");
		UUID child = profile(null, "CHILD", "Zosia", "Dąbrowska");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", guardian)
			.param("child", child)
			.update();
		UUID pass = pass(child, 4, 3);
		ledger(pass, null, "ACTIVATION", 4, guardianAccount, null, -100);
		UUID booking = reservation(child, pass, "Pole dance dzieci", guardianAccount, "SELF", 24);
		ledger(pass, booking, "ENTRY_RESERVED", -1, guardianAccount, null, -1);

		String body = history(child, pass, "").andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(item(body, 0)).containsEntry("actor", Map.of("name", "Monika Dąbrowska", "role", "GUARDIAN"));
		history(adult, pass, "").andExpect(status().isNotFound());
	}

	@Test
	void aSuspensionCutShortShowsTheDaysItGaveBack() throws Exception {
		UUID pass = pass(adult, 8, 8);
		jdbc.sql("UPDATE customer_pass SET valid_until = :until WHERE id = :id")
			.param("until", today().plusDays(20))
			.param("id", pass)
			.update();
		ledger(pass, null, "ACTIVATION", 8, admin, null, -100);
		UUID suspension = jdbc.sql("""
				INSERT INTO pass_suspension (pass_id, starts_on, ends_on, reason, created_by, created_at)
				VALUES (:pass, :startsOn, :endsOn, 'Choroba', :admin, :at) RETURNING id
				""")
			.param("pass", pass)
			.param("startsOn", today().minusDays(2))
			.param("endsOn", today().plusDays(3))
			.param("admin", admin)
			.param("at", at(-72))
			.query(UUID.class)
			.single();
		UUID suspended = ledger(pass, null, "EXTENSION", 0, admin, "Choroba", -72);
		jdbc.sql("""
				UPDATE pass_ledger SET suspension_id = :suspension, valid_until_before = :before, valid_until_after = :after
				WHERE id = :id
				""")
			.param("suspension", suspension)
			.param("before", today().plusDays(14))
			.param("after", today().plusDays(20))
			.param("id", suspended)
			.update();
		perform("admin", post(passPath(adult, pass) + "/suspensions/" + suspension + "/cancel"), "{ \"version\": 0 }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.validUntil").value(today().plusDays(16).toString()));

		String body = history(adult, pass, "?category=VALIDITY").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.items[*].kind", contains("SUSPENSION_CANCELLED", "SUSPENDED")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(item(body, 0)).containsEntry("reason", null)
			.containsEntry("suspension", Map.of("startsOn", today().toString(), "endsOn", today().plusDays(3).toString()));
		assertThat(item(body, 1)).containsEntry("reason", "Choroba")
			.containsEntry("suspension", Map.of("startsOn", today().minusDays(2).toString(), "endsOn",
					today().plusDays(3).toString()));
	}

	@Test
	void entriesSavedBeforeTheyWereLinkedToTheirSuspensionStillReadSensibly() throws Exception {
		UUID pass = pass(adult, 8, 8);
		ledger(pass, null, "ACTIVATION", 8, admin, null, -100);
		UUID extension = ledger(pass, null, "EXTENSION", 0, admin, "Zawieszenie na 7 dni", -50);
		UUID cancellation = ledger(pass, null, "EXTENSION", 0, admin, "Suspension cancelled", -40);
		ledger(pass, null, "EXPIRATION", 0, null, null, -1);
		validity(extension, today().plusDays(10), today().plusDays(17));
		validity(cancellation, today().plusDays(17), today().plusDays(12));

		String body = history(adult, pass, "?category=VALIDITY").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.items[*].kind", contains("EXPIRED", "SUSPENSION_CANCELLED", "EXTENDED")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(item(body, 0)).containsEntry("actor", null).containsEntry("entriesChange", null);
		assertThat(item(body, 1)).containsEntry("reason", null).containsEntry("suspension", null);
		assertThat(item(body, 2)).containsEntry("reason", "Zawieszenie na 7 dni").containsEntry("suspension", null);
	}

	@Test
	void anUnlimitedPassHasNoBalance() throws Exception {
		UUID pass = pass(adult, null, null);
		ledger(pass, null, "ACTIVATION", 0, admin, null, -100);
		UUID booking = reservation(adult, pass, "Open", client, "SELF", -30);
		ledger(pass, booking, "ENTRY_RESERVED", 0, client, null, -50);

		String body = history(adult, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.pass.entriesTotal").isEmpty())
			.andExpect(jsonPath("$.pass.ledgerBalance").isEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(item(body, 0)).containsEntry("entriesChange", null).containsEntry("balanceAfter", null);
		assertThat(item(body, 1)).containsEntry("entriesChange", null).containsEntry("balanceAfter", null);
	}

	@Test
	void onlyAdministratorsReadAPassOfTheGivenParticipant() throws Exception {
		UUID pass = pass(adult, 8, 8);
		UUID other = profile(null, "ADULT", "Piotr", "Wójcik");
		account("trainer", "TRAINER");
		long ledger = jdbc.sql("SELECT count(*) FROM pass_ledger").query(Long.class).single();

		history(adult, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.totalItems").value(0))
			.andExpect(jsonPath("$.entries.items", hasSize(0)));
		history(other, pass, "").andExpect(status().isNotFound());
		history(adult, UUID.randomUUID(), "").andExpect(status().isNotFound());
		for (String suffix : List.of("?category=OTHER", "?page=-1", "?size=0", "?size=101")) {
			history(adult, pass, suffix).andExpect(status().isBadRequest());
		}
		perform("client", get(historyPath(adult, pass)), null).andExpect(status().isForbidden());
		perform("trainer", get(historyPath(adult, pass)), null).andExpect(status().isForbidden());
		mvc.perform(get(historyPath(adult, pass))).andExpect(status().isUnauthorized());
		assertThat(jdbc.sql("SELECT count(*) FROM pass_ledger").query(Long.class).single()).isEqualTo(ledger);
	}

	/** V29 links the entries saved before it to their suspensions; its updates are run again here. */
	@Test
	void theMigrationLinksEarlierEntriesToTheirSuspensions() throws Exception {
		UUID pass = pass(adult, 8, 8);
		UUID suspended = suspension(pass, today().plusDays(3), today().plusDays(5), "Wyjazd", -50, null);
		UUID suspendedEntry = extension(pass, "Wyjazd", -50, 29, 32);
		UUID extendedEntry = extension(pass, "Kontuzja", -50, 32, 42);
		UUID whole = suspension(pass, today().plusDays(10), today().plusDays(11), null, -40, -30);
		UUID wholeEntry = extension(pass, "Suspension cancelled", -30, 42, 40);
		UUID cutShort = suspension(pass, today().minusDays(3), today().minusDays(1), null, -100, null);
		UUID cutShortEntry = extension(pass, "Suspension cancelled", 0, 40, 37);

		String migration = new String(new org.springframework.core.io.ClassPathResource(
				"db/migration/V29__pass_ledger_suspension.sql").getInputStream().readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8);
		for (String statement : migration.replaceAll("(?m)^--.*$", "").split(";")) {
			if (statement.strip().startsWith("UPDATE")) {
				jdbc.sql(statement).update();
			}
		}

		Map<UUID, UUID> links = new java.util.HashMap<>();
		jdbc.sql("SELECT id, suspension_id FROM pass_ledger WHERE pass_id = :pass")
			.param("pass", pass)
			.query((rs, row) -> links.put(rs.getObject("id", UUID.class), rs.getObject("suspension_id", UUID.class)))
			.list();
		assertThat(links).containsEntry(suspendedEntry, suspended)
			.containsEntry(extendedEntry, null)
			.containsEntry(wholeEntry, whole)
			.containsEntry(cutShortEntry, cutShort);
	}

	private UUID suspension(UUID pass, LocalDate startsOn, LocalDate endsOn, String reason, int createdHours,
			Integer cancelledHours) {
		return jdbc.sql("""
				INSERT INTO pass_suspension (pass_id, starts_on, ends_on, reason, created_by, created_at, cancelled_at)
				VALUES (:pass, :startsOn, :endsOn, :reason, :admin, :created, :cancelled) RETURNING id
				""")
			.param("pass", pass)
			.param("startsOn", startsOn)
			.param("endsOn", endsOn)
			.param("reason", reason, java.sql.Types.VARCHAR)
			.param("admin", admin)
			.param("created", at(createdHours))
			.param("cancelled", (cancelledHours == null) ? null : at(cancelledHours), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
			.query(UUID.class)
			.single();
	}

	/** An extension entry, a few milliseconds after the given hour, moving the last day between days from today. */
	private UUID extension(UUID pass, String reason, int hours, int beforeDays, int afterDays) {
		UUID entry = ledger(pass, null, "EXTENSION", 0, admin, reason, hours);
		jdbc.sql("UPDATE pass_ledger SET occurred_at = occurred_at + INTERVAL '20 milliseconds' WHERE id = :id")
			.param("id", entry)
			.update();
		validity(entry, today().plusDays(beforeDays), today().plusDays(afterDays));
		return entry;
	}

	private UUID account(String name, String role) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@pass-history.example.com")
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", "history-" + name)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id)
			.param("role", role)
			.update();
		given(jwtDecoder.decode(name))
			.willReturn(Jwt.withTokenValue(name).header("alg", "RS256").subject("history-" + name).build());
		return id;
	}

	private UUID profile(UUID account, String kind, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, :firstName, :lastName) RETURNING id
				""")
			.param("account", account, java.sql.Types.OTHER)
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.query(UUID.class)
			.single();
	}

	private UUID pass(UUID customer, Integer total, Integer remaining) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				                           price_paid, status, valid_from, valid_until, created_by)
				VALUES (:customer, :variant, 'Karnet Pole Dance', :total, :remaining, 260, 'ACTIVE', :from, :until, :admin)
				RETURNING id
				""")
			.param("customer", customer)
			.param("variant", variant)
			.param("total", total, java.sql.Types.SMALLINT)
			.param("remaining", remaining, java.sql.Types.SMALLINT)
			.param("from", today().minusDays(10))
			.param("until", today().plusDays(19))
			.param("admin", admin)
			.query(UUID.class)
			.single();
	}

	/** A reservation of an event starting the given number of hours from now. */
	private UUID reservation(UUID customer, UUID pass, String title, UUID bookedBy, String source, int startsInHours) {
		UUID event = jdbc.sql("""
				INSERT INTO schedule_event (kind, visibility, title, starts_at, ends_at)
				VALUES ('CLASS', 'PUBLIC', :title, :starts, :ends) RETURNING id
				""")
			.param("title", title)
			.param("starts", at(startsInHours))
			.param("ends", at(startsInHours + 1))
			.query(UUID.class)
			.single();
		return jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, customer_pass_id, booked_by, source)
				VALUES (:event, :customer, :pass, :bookedBy, :source) RETURNING id
				""")
			.param("event", event)
			.param("customer", customer)
			.param("pass", pass, java.sql.Types.OTHER)
			.param("bookedBy", bookedBy)
			.param("source", source)
			.query(UUID.class)
			.single();
	}

	private UUID ledger(UUID pass, UUID reservation, String kind, int delta, UUID actor, String reason, int hours) {
		return jdbc.sql("""
				INSERT INTO pass_ledger (pass_id, reservation_id, kind, entries_delta, reason, actor_account_id, occurred_at)
				VALUES (:pass, :reservation, :kind, :delta, :reason, :actor, :at) RETURNING id
				""")
			.param("pass", pass)
			.param("reservation", reservation, java.sql.Types.OTHER)
			.param("kind", kind)
			.param("delta", delta)
			.param("reason", reason, java.sql.Types.VARCHAR)
			.param("actor", actor, java.sql.Types.OTHER)
			.param("at", at(hours))
			.query(UUID.class)
			.single();
	}

	private void validity(UUID ledgerEntry, LocalDate before, LocalDate after) {
		jdbc.sql("UPDATE pass_ledger SET valid_until_before = :before, valid_until_after = :after WHERE id = :id")
			.param("before", before)
			.param("after", after)
			.param("id", ledgerEntry)
			.update();
	}

	private OffsetDateTime at(int hours) {
		return OffsetDateTime.ofInstant(now.plus(hours, ChronoUnit.HOURS), ZoneOffset.UTC);
	}

	private static LocalDate today() {
		return LocalDate.now(STUDIO_ZONE);
	}

	private static Map<String, Object> item(String body, int index) {
		return JsonPath.read(body, "$.entries.items[" + index + "]");
	}

	private static Map<String, Object> session(String body, int index) {
		return JsonPath.read(body, "$.entries.items[" + index + "].session");
	}

	private ResultActions history(UUID customer, UUID pass, String suffix) throws Exception {
		return perform("admin", get(historyPath(customer, pass) + suffix), null);
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private static String passesOf(UUID customer) {
		return "/api/v1/customers/" + customer + "/passes";
	}

	private static String passPath(UUID customer, UUID pass) {
		return passesOf(customer) + "/" + pass;
	}

	private static String historyPath(UUID customer, UUID pass) {
		return passPath(customer, pass) + "/history";
	}

}
