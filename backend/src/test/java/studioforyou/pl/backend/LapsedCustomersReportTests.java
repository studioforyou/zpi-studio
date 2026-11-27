package studioforyou.pl.backend;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

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
 * Administrators list customers who stopped coming (#171). Events are placed at noon of studio dates
 * counted from today, so the number of days since a visit does not depend on the time the tests run.
 * Without a studio row the studio's time zone is Europe/Warsaw.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LapsedCustomersReportTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	private static final ZoneId STUDIO_ZONE = ZoneId.of("Europe/Warsaw");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminAccount;

	private UUID variant;

	@BeforeEach
	void setUp() {
		adminAccount = addAccount("admin@example.com", "ADMIN", "ACTIVE");
		signIn(adminAccount, "uid-admin", ADMIN_TOKEN);
		signIn(addAccount("trainer@example.com", "TRAINER", "ACTIVE"), "uid-trainer", TRAINER_TOKEN);
		UUID product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('Karnet', 'ENTRY_PASS', 'ADULT') RETURNING id
				""").query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 260.00) RETURNING id
				""").param("product", product).query(UUID.class).single();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM announcement").update();
		jdbc.sql("DELETE FROM document_acceptance").update();
		jdbc.sql("DELETE FROM reservation").update();
		jdbc.sql("DELETE FROM schedule_event").update();
		jdbc.sql("DELETE FROM customer_pass").update();
		jdbc.sql("DELETE FROM guardianship").update();
		jdbc.sql("DELETE FROM customer_profile").update();
		jdbc.sql("DELETE FROM pass_product_variant").update();
		jdbc.sql("DELETE FROM pass_product").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%@example.com'").update();
	}

	@Test
	void listsVisitorsWithoutABookingWhoseLastVisitIsOldEnoughFromTheLongestAbsent() throws Exception {
		UUID anna = addClient("anna@example.com", "Anna", "+48 600 100 200");
		visit(anna, 140, "ACTIVE", "UNMARKED", "SCHEDULED");
		visit(anna, 45, "ACTIVE", "PRESENT", "SCHEDULED");
		visit(anna, 40, "ACTIVE", "UNMARKED", "SCHEDULED");
		UUID bartek = addClient("bartek@example.com", "Bartek", null);
		visit(bartek, 60, "ACTIVE", "PRESENT", "SCHEDULED");
		// A later absence, a cancelled booking and a class the studio cancelled are not visits.
		visit(bartek, 20, "ACTIVE", "ABSENT", "SCHEDULED");
		visit(bartek, 15, "CANCELLED", "UNMARKED", "SCHEDULED");
		visit(bartek, 10, "ACTIVE", "UNMARKED", "CANCELLED");
		UUID recent = addClient("recent@example.com", "Recent", null);
		visit(recent, 10, "ACTIVE", "PRESENT", "SCHEDULED");
		UUID booked = addClient("booked@example.com", "Booked", null);
		visit(booked, 50, "ACTIVE", "PRESENT", "SCHEDULED");
		visit(booked, -3, "ACTIVE", "UNMARKED", "SCHEDULED");
		UUID onlyAbsent = addClient("absent@example.com", "Absent", null);
		visit(onlyAbsent, 50, "ACTIVE", "ABSENT", "SCHEDULED");

		report(30).andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf").value(today().toString()))
			.andExpect(jsonPath("$.inactiveDays").value(30))
			.andExpect(jsonPath("$.everVisited").value(4))
			.andExpect(jsonPath("$.customers.length()").value(2))
			.andExpect(jsonPath("$.customers[0].profileId").value(bartek.toString()))
			.andExpect(jsonPath("$.customers[0].lastVisit").value(today().minusDays(60).toString()))
			.andExpect(jsonPath("$.customers[0].daysSinceLastVisit").value(60))
			.andExpect(jsonPath("$.customers[0].recentVisits").value(1))
			.andExpect(jsonPath("$.customers[1].profileId").value(anna.toString()))
			.andExpect(jsonPath("$.customers[1].kind").value("ADULT"))
			.andExpect(jsonPath("$.customers[1].firstName").value("Anna"))
			.andExpect(jsonPath("$.customers[1].email").value("anna@example.com"))
			.andExpect(jsonPath("$.customers[1].phone").value("+48 600 100 200"))
			.andExpect(jsonPath("$.customers[1].guardianName").value(nullValue()))
			.andExpect(jsonPath("$.customers[1].daysSinceLastVisit").value(40))
			// 40 and 45 days ago; the visit 140 days ago is more than 90 days before the last one.
			.andExpect(jsonPath("$.customers[1].recentVisits").value(2))
			.andExpect(jsonPath("$.customers[1].pass").value(nullValue()))
			.andExpect(jsonPath("$.customers[1].marketingConsent").value(false))
			.andExpect(jsonPath("$.customers[1].lastAnnouncement").value(nullValue()));

		report(7).andExpect(jsonPath("$.customers.length()").value(3))
			.andExpect(jsonPath("$.customers[2].profileId").value(recent.toString()));
		report(41).andExpect(jsonPath("$.customers.length()").value(1));
	}

	@Test
	void reachesAChildThroughItsGuardian() throws Exception {
		UUID parentAccount = addAccount("parent@example.com", "CLIENT", "ACTIVE");
		UUID parent = insertProfile(parentAccount, "ADULT", "Marta", "+48 700 800 900", "ACTIVE");
		UUID child = insertProfile(null, "CHILD", "Zosia", null, "ACTIVE");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", parent).param("child", child).update();
		visit(child, 35, "ACTIVE", "PRESENT", "SCHEDULED");
		consent(parent);
		announce(parentAccount, "PERSON", today().minusDays(20));
		announce(parentAccount, "PERSON", today().minusDays(5));
		announce(parentAccount, "ALL", today().minusDays(1));

		report(30).andExpect(status().isOk())
			.andExpect(jsonPath("$.customers.length()").value(1))
			.andExpect(jsonPath("$.customers[0].profileId").value(child.toString()))
			.andExpect(jsonPath("$.customers[0].kind").value("CHILD"))
			.andExpect(jsonPath("$.customers[0].contactAccountId").value(parentAccount.toString()))
			.andExpect(jsonPath("$.customers[0].guardianName").value("Marta Testowa"))
			.andExpect(jsonPath("$.customers[0].email").value("parent@example.com"))
			.andExpect(jsonPath("$.customers[0].phone").value("+48 700 800 900"))
			.andExpect(jsonPath("$.customers[0].marketingConsent").value(true))
			.andExpect(jsonPath("$.customers[0].lastAnnouncement").value(today().minusDays(5).toString()));
	}

	@Test
	void leavesOutSuspendedAccountsAndArchivedProfiles() throws Exception {
		UUID suspended = insertProfile(addAccount("suspended@example.com", "CLIENT", "SUSPENDED"), "ADULT",
				"Suspended", null, "ACTIVE");
		visit(suspended, 40, "ACTIVE", "PRESENT", "SCHEDULED");
		UUID archived = insertProfile(null, "ADULT", "Archived", null, "ARCHIVED");
		visit(archived, 40, "ACTIVE", "PRESENT", "SCHEDULED");
		UUID withoutAccount = insertProfile(null, "ADULT", "Offline", null, "ACTIVE");
		visit(withoutAccount, 40, "ACTIVE", "PRESENT", "SCHEDULED");

		report(30).andExpect(jsonPath("$.everVisited").value(1))
			.andExpect(jsonPath("$.customers.length()").value(1))
			.andExpect(jsonPath("$.customers[0].profileId").value(withoutAccount.toString()))
			.andExpect(jsonPath("$.customers[0].contactAccountId").value(nullValue()))
			.andExpect(jsonPath("$.customers[0].email").value(nullValue()));
	}

	@Test
	void showsThePassThatTellsWhetherTheyCanStillCome() throws Exception {
		UUID valid = addClient("valid@example.com", "Valid", null);
		insertPass(valid, "EXPIRED", today().minusDays(70), today().minusDays(40), 0, "Stary");
		insertPass(valid, "ACTIVE", today().minusDays(39), today().plusDays(10), 3, "Karnet 8");
		UUID usedUp = addClient("usedup@example.com", "UsedUp", null);
		insertPass(usedUp, "ACTIVE", today().minusDays(39), today().plusDays(10), 0, "Karnet 8");
		UUID suspended = addClient("paused@example.com", "Paused", null);
		insertPass(suspended, "SUSPENDED", today().minusDays(39), today().plusDays(30), 5, "Karnet 8");
		UUID ignored = addClient("ignored@example.com", "Ignored", null);
		insertPass(ignored, "CANCELLED", today().minusDays(39), today().plusDays(10), 8, "Karnet 8");
		insertPass(ignored, "PENDING_PAYMENT", null, null, 8, "Karnet 8");
		for (UUID customer : new UUID[] { valid, usedUp, suspended, ignored }) {
			visit(customer, 40, "ACTIVE", "PRESENT", "SCHEDULED");
		}

		report(30).andExpect(jsonPath("$.customers.length()").value(4))
			.andExpect(jsonPath("$.customers[?(@.firstName == 'Valid')].pass.state").value("VALID"))
			.andExpect(jsonPath("$.customers[?(@.firstName == 'Valid')].pass.productName").value("Karnet 8"))
			.andExpect(jsonPath("$.customers[?(@.firstName == 'Valid')].pass.entriesRemaining").value(3))
			.andExpect(jsonPath("$.customers[?(@.firstName == 'Valid')].pass.validUntil")
				.value(today().plusDays(10).toString()))
			.andExpect(jsonPath("$.customers[?(@.firstName == 'UsedUp')].pass.state").value("ENDED"))
			.andExpect(jsonPath("$.customers[?(@.firstName == 'Paused')].pass.state").value("SUSPENDED"))
			.andExpect(jsonPath("$.customers[?(@.firstName == 'Ignored')].pass").value(contains(nullValue())));
	}

	@Test
	void rejectsAThresholdOutOfRange() throws Exception {
		report(0).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("inactiveDays"));
		report(366).andExpect(status().isBadRequest());
		report(365).andExpect(status().isOk());
	}

	@Test
	void isOnlyForAdministrators() throws Exception {
		mvc.perform(get("/api/v1/reports/lapsed-customers").header("Authorization", "Bearer " + TRAINER_TOKEN))
			.andExpect(status().isForbidden());
	}

	private ResultActions report(int inactiveDays) throws Exception {
		return mvc.perform(get("/api/v1/reports/lapsed-customers").param("inactiveDays", String.valueOf(inactiveDays))
			.header("Authorization", "Bearer " + ADMIN_TOKEN));
	}

	private static LocalDate today() {
		return LocalDate.now(STUDIO_ZONE);
	}

	private UUID addClient(String email, String firstName, @Nullable String phone) {
		return insertProfile(addAccount(email, "CLIENT", "ACTIVE"), "ADULT", firstName, phone, "ACTIVE");
	}

	private UUID addAccount(String email, String roleCode, String status) {
		UUID id = jdbc.sql("""
				INSERT INTO account (email, status, activated_at) VALUES (:email, :status, now()) RETURNING id
				""")
			.param("email", email)
			.param("status", status)
			.query(UUID.class)
			.single();
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

	private UUID insertProfile(@Nullable UUID account, String kind, String firstName, @Nullable String phone,
			String status) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name, phone, status)
				VALUES (:account, :kind, :firstName, 'Testowa', :phone, :status)
				RETURNING id
				""")
			.param("account", account)
			.param("kind", kind)
			.param("firstName", firstName)
			.param("phone", phone)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

	/** A reservation for a class at noon {@code daysAgo} studio days before today (negative: ahead). */
	private void visit(UUID customer, int daysAgo, String reservationStatus, String attendance, String eventStatus) {
		OffsetDateTime start = today().minusDays(daysAgo).atTime(LocalTime.NOON).atZone(STUDIO_ZONE).toOffsetDateTime();
		UUID event = jdbc.sql("""
				INSERT INTO schedule_event (kind, starts_at, ends_at, status)
				VALUES ('CLASS', :startsAt, :endsAt, :status)
				RETURNING id
				""")
			.param("startsAt", start)
			.param("endsAt", start.plusHours(1))
			.param("status", eventStatus)
			.query(UUID.class)
			.single();
		jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, status, attendance)
				VALUES (:event, :customer, :bookedBy, :status, :attendance)
				""")
			.param("event", event)
			.param("customer", customer)
			.param("bookedBy", adminAccount)
			.param("status", reservationStatus)
			.param("attendance", attendance)
			.update();
	}

	private void insertPass(UUID customer, String status, @Nullable LocalDate validFrom, @Nullable LocalDate validUntil,
			int entriesRemaining, String productName) {
		jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, price_paid, status, valid_from,
				                           valid_until, entries_total, entries_remaining)
				VALUES (:customer, :variant, :productName, 260.00, :status, :validFrom, :validUntil, 8, :remaining)
				""")
			.param("customer", customer)
			.param("variant", variant)
			.param("productName", productName)
			.param("status", status)
			.param("validFrom", validFrom)
			.param("validUntil", validUntil)
			.param("remaining", entriesRemaining)
			.update();
	}

	private void consent(UUID profile) {
		jdbc.sql("""
				INSERT INTO document_acceptance (document_version_id, customer_id, accepted_by)
				SELECT v.id, :profile, :admin FROM document_version v JOIN document d ON d.id = v.document_id
				WHERE d.code = 'MARKETING_PUSH' AND v.version_no = 1
				""").param("profile", profile).param("admin", adminAccount).update();
	}

	private void announce(UUID account, String audience, LocalDate sentOn) {
		jdbc.sql("""
				INSERT INTO announcement (author_id, audience, target_account_id, title, body, sent_at)
				VALUES (:admin, :audience, :target, 'Tęsknimy', 'Wróć do nas', :sentAt)
				""")
			.param("admin", adminAccount)
			.param("audience", audience)
			.param("target", "PERSON".equals(audience) ? account : null)
			.param("sentAt", sentOn.atTime(LocalTime.NOON).atZone(STUDIO_ZONE).toOffsetDateTime())
			.update();
	}

}
