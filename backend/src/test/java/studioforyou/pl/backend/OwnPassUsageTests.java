package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Real PostgreSQL accounting, batching, profile authorization and HTTP validation. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional(isolation = Isolation.REPEATABLE_READ)
class OwnPassUsageTests {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcClient jdbc;
	@MockitoBean private JwtDecoder jwtDecoder;

	private UUID account;
	private UUID own;
	private UUID child;
	private UUID other;
	private UUID pass;
	private UUID variant;
	private Instant now;

	@BeforeEach
	void setUp() {
		now = Instant.now();
		account = account("parent");
		own = profile(account, "ADULT");
		other = profile(account("other"), "ADULT");
		child = profile(null, "CHILD");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:own, :child)")
			.param("own", own).param("child", child).update();
		UUID product = jdbc.sql("INSERT INTO pass_product (name, kind, audience) VALUES ('Usage product', 'ENTRY_PASS', 'ANY') RETURNING id")
			.query(UUID.class).single();
		variant = jdbc.sql("INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price) VALUES (:id, 8, 30, 100) RETURNING id")
			.param("id", product).query(UUID.class).single();
		pass = pass(own, false);
	}

	@Test
	void groupsModernAccountingWithoutDoubleChargingAndKeepsLegacyCorrectionsAndRebookings() throws Exception {
		UUID consumed = reservation(own, pass, "Consumed", false);
		ledger(pass, consumed, "ENTRY_RESERVED", -1, -8);
		ledger(pass, consumed, "ENTRY_CONSUMED", 0, -7);
		UUID legacy = reservation(own, pass, "Legacy", false);
		ledger(pass, legacy, "ENTRY_CONSUMED", -1, -6);
		UUID reserved = reservation(own, pass, "Reserved", false);
		ledger(pass, reserved, "ENTRY_RESERVED", -1, -5);
		UUID returned = reservation(own, pass, "Returned", false);
		ledger(pass, returned, "ENTRY_RESERVED", -1, -4);
		ledger(pass, returned, "ENTRY_RETURNED", 1, -3);
		jdbc.sql("UPDATE reservation SET status = 'CANCELLED' WHERE id = :id").param("id", returned).update();
		// Booking the same dated class again is a separate reservation, not another row for the old debit.
		UUID rebooked = jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, customer_pass_id, booked_by)
				SELECT event_id, customer_id, customer_pass_id, booked_by FROM reservation WHERE id = :id RETURNING id
				""").param("id", returned).query(UUID.class).single();
		ledger(pass, rebooked, "ENTRY_RESERVED", -1, -2);
		ledger(pass, null, "CORRECTION", 2, -1);
		ledger(pass, null, "ACTIVATION", 8, -10);
		ledger(pass, null, "EXTENSION", 0, -9);
		String body = perform("parent", own, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(6)).andExpect(jsonPath("$.items", hasSize(6)))
			.andReturn().getResponse().getContentAsString();
		assertThat(item(body, consumed)).containsEntry("kind", "CONSUMED").containsEntry("entriesChange", -1);
		assertThat(item(body, legacy)).containsEntry("kind", "CONSUMED").containsEntry("entriesChange", -1);
		assertThat(item(body, reserved)).containsEntry("kind", "RESERVED").containsEntry("entriesChange", -1);
		assertThat(item(body, returned)).containsEntry("kind", "RETURNED").containsEntry("entriesChange", 0)
			.containsEntry("cancelled", true);
		assertThat(item(body, rebooked)).containsEntry("kind", "RESERVED");
		assertThat(JsonPath.<String>read(body, "$.items[0].kind")).isEqualTo("CORRECTION");
		assertThat(JsonPath.<Integer>read(body, "$.items[0].entriesChange")).isEqualTo(2);
		assertThat(body).doesNotContain("internal-only", "reason", "actorAccountId", "bookedBy", "phone");
	}

	@Test
	void supportsExpiredPassesAndPrivatePersonalSessionsWithoutChangingTheBalance() throws Exception {
		UUID booking = reservation(own, pass, "Private PT", true);
		ledger(pass, booking, "ENTRY_CONSUMED", -1, -1);
		jdbc.sql("UPDATE customer_pass SET status = 'EXPIRED', valid_until = CURRENT_DATE - 1 WHERE id = :id")
			.param("id", pass).update();
		Map<String, Object> before = jdbc.sql("SELECT * FROM customer_pass WHERE id = :id").param("id", pass).query().singleRow();
		long ledgerCount = jdbc.sql("SELECT count(*) FROM pass_ledger").query(Long.class).single();
		perform("parent", own, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value("Private PT"))
			.andExpect(jsonPath("$.items[0].startsAt").isNotEmpty());
		assertThat(jdbc.sql("SELECT * FROM customer_pass WHERE id = :id").param("id", pass).query().singleRow()).isEqualTo(before);
		assertThat(jdbc.sql("SELECT count(*) FROM pass_ledger").query(Long.class).single()).isEqualTo(ledgerCount);
	}

	@Test
	void unlimitedUsageHasNoNumericDebitAndEmptyPassesHaveAnEmptyPage() throws Exception {
		UUID unlimited = pass(own, true);
		UUID booking = reservation(own, unlimited, "Open", false);
		ledger(unlimited, booking, "ENTRY_RESERVED", 0, -2);
		ledger(unlimited, booking, "ENTRY_CONSUMED", 0, -1);
		perform("parent", own, unlimited, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].kind").value("CONSUMED"))
			.andExpect(jsonPath("$.items[0].entriesChange").isEmpty());
		perform("parent", own, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(0)).andExpect(jsonPath("$.totalPages").value(0))
			.andExpect(jsonPath("$.items", hasSize(0)));
	}

	@Test
	void correctionOnlyHistoryPagesInStableTimeAndIdOrder() throws Exception {
		UUID first = ledger(pass, null, "CORRECTION", -1, -1);
		UUID second = ledger(pass, null, "CORRECTION", 1, -1);
		String larger = first.toString().compareTo(second.toString()) > 0 ? first.toString() : second.toString();
		String smaller = larger.equals(first.toString()) ? second.toString() : first.toString();
		perform("parent", own, pass, "?size=1").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(larger)).andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.totalPages").value(2));
		perform("parent", own, pass, "?size=1&page=1").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(smaller));
		perform("parent", own, pass, "?size=1&page=20").andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalItems").value(2));
	}

	@Test
	void protectsProfileAndPassPairsAndEndsAccessWithGuardianship() throws Exception {
		UUID childPass = pass(child, false);
		UUID booking = reservation(child, childPass, "Child training", false);
		ledger(childPass, booking, "ENTRY_RESERVED", -1, -1);
		perform("parent", child, childPass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value("Child training"));
		perform("other", child, childPass, "").andExpect(status().isNotFound());
		perform("parent", own, childPass, "").andExpect(status().isNotFound());
		perform("parent", child, pass, "").andExpect(status().isNotFound());
		perform("parent", own, UUID.randomUUID(), "").andExpect(status().isNotFound());
		perform("parent", UUID.randomUUID(), pass, "").andExpect(status().isNotFound());
		jdbc.sql("UPDATE guardianship SET ended_at = CURRENT_TIMESTAMP WHERE child_id = :id").param("id", child).update();
		perform("parent", child, childPass, "").andExpect(status().isNotFound());
		jdbc.sql("UPDATE customer_profile SET status = 'ARCHIVED' WHERE id = :id").param("id", own).update();
		perform("parent", own, pass, "").andExpect(status().isNotFound());
	}

	@Test
	void marksInconsistentAndForeignReservationLinksWithoutLeakingTheirDetails() throws Exception {
		UUID foreignPass = pass(other, false);
		UUID foreign = reservation(other, foreignPass, "Private foreign title", true);
		ledger(pass, foreign, "ENTRY_RESERVED", -1, -1);
		UUID inconsistent = reservation(own, pass, "Inconsistent", false);
		ledger(pass, inconsistent, "ENTRY_RESERVED", -1, -3);
		ledger(pass, inconsistent, "ENTRY_CONSUMED", -1, -2); // A modern charge cannot debit twice.
		String body = perform("parent", own, pass, "").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].kind").value("REVIEW"))
			.andExpect(jsonPath("$.items[0].reservationId").isEmpty())
			.andExpect(jsonPath("$.items[0].eventId").isEmpty())
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("Private foreign title", foreign.toString(), foreignPass.toString());
		assertThat(item(body, inconsistent)).containsEntry("kind", "REVIEW").containsEntry("entriesChange", null);
	}

	@Test
	void accountHistoryCombinesOwnedProfilesWithGlobalPagingAndNoForeignData() throws Exception {
		UUID childPass = pass(child, false);
		UUID ownBooking = reservation(own, pass, "Adult session", false);
		UUID childBooking = reservation(child, childPass, "Child session", false);
		ledger(pass, ownBooking, "ENTRY_CONSUMED", -1, -3);
		ledger(childPass, childBooking, "ENTRY_CONSUMED", -1, -2);
		ledger(pass, null, "CORRECTION", 1, -1);
		UUID foreignPass = pass(other, false);
		ledger(foreignPass, reservation(other, foreignPass, "Foreign secret", true), "ENTRY_CONSUMED", -1, 0);
		String body = accountHistory("").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.totalItems").value(3))
			.andExpect(jsonPath("$.participants", hasSize(2)))
			.andExpect(jsonPath("$.products", hasSize(1)))
			.andExpect(jsonPath("$.entries.items[0].kind").value("CORRECTION"))
			.andExpect(jsonPath("$.entries.items[1].profileId").value(child.toString()))
			.andExpect(jsonPath("$.entries.items[1].passId").value(childPass.toString()))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("Foreign secret", foreignPass.toString(), other.toString());
		accountHistory("?size=1&page=1").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.items[0].title").value("Child session"))
			.andExpect(jsonPath("$.entries.totalItems").value(3))
			.andExpect(jsonPath("$.entries.totalPages").value(3));
	}

	@Test
	void accountFiltersUsePassProductsIncludingArchivedProductsAndExpiredPasses() throws Exception {
		UUID childPass = pass(child, false);
		UUID product = jdbc.sql("INSERT INTO pass_product (name, kind, audience, is_active) VALUES ('Archived kids', 'ENTRY_PASS', 'CHILD', FALSE) RETURNING id")
			.query(UUID.class).single();
		UUID childVariant = jdbc.sql("INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price, is_active) VALUES (:id, 4, 30, 100, FALSE) RETURNING id")
			.param("id", product).query(UUID.class).single();
		jdbc.sql("UPDATE customer_pass SET variant_id = :variant, status = 'EXPIRED' WHERE id = :id")
			.param("variant", childVariant).param("id", childPass).update();
		ledger(pass, null, "CORRECTION", 1, -2);
		ledger(childPass, reservation(child, childPass, "Old kids session", false), "ENTRY_CONSUMED", -1, -1);
		accountHistory("?productId=" + product).andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.totalItems").value(1))
			.andExpect(jsonPath("$.entries.items[0].title").value("Old kids session"))
			.andExpect(jsonPath("$.entries.items[0].productId").value(product.toString()))
			.andExpect(jsonPath("$.products", hasSize(2)));
		accountHistory("?profileId=" + own + "&productId=" + product).andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.totalItems").value(0));
		accountHistory("?profileId=" + child + "&passId=" + childPass).andExpect(status().isOk())
			.andExpect(jsonPath("$.selectedPassName").value("Saved pass name"))
			.andExpect(jsonPath("$.entries.totalItems").value(1));
	}

	@Test
	void dateBoundsFilterTheLatestGroupedAccountingChangeBeforePaging() throws Exception {
		UUID booking = reservation(own, pass, "Inside range", false);
		ledger(pass, booking, "ENTRY_RESERVED", -1, -8);
		ledger(pass, booking, "ENTRY_CONSUMED", 0, -2);
		ledger(pass, null, "CORRECTION", 1, 0);
		accountHistory("?from=" + now.minusSeconds(7200) + "&to=" + now + "&size=1")
			.andExpect(status().isOk()).andExpect(jsonPath("$.entries.totalItems").value(1))
			.andExpect(jsonPath("$.entries.items[0].title").value("Inside range"));
		accountHistory("?from=" + now + "&to=" + now.minusSeconds(1)).andExpect(status().isBadRequest());
		for (String invalid : List.of("?from=bad", "?page=-1", "?size=101", "?productId=bad")) {
			accountHistory(invalid).andExpect(status().isBadRequest());
		}
	}

	@Test
	void accountHistoryImmediatelyDropsAnEndedGuardianshipAndRejectsForeignFilters() throws Exception {
		UUID childPass = pass(child, false);
		ledger(childPass, null, "CORRECTION", 1, -1);
		accountHistory("").andExpect(status().isOk()).andExpect(jsonPath("$.entries.totalItems").value(1));
		accountHistory("?profileId=" + other).andExpect(status().isNotFound());
		accountHistory("?passId=" + UUID.randomUUID()).andExpect(status().isNotFound());
		accountHistory("?productId=" + UUID.randomUUID()).andExpect(status().isNotFound());
		accountHistory("?profileId=" + own + "&passId=" + childPass).andExpect(status().isNotFound());
		jdbc.sql("UPDATE guardianship SET ended_at = CURRENT_TIMESTAMP WHERE child_id = :id").param("id", child).update();
		String body = accountHistory("").andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.totalItems").value(0)).andExpect(jsonPath("$.participants", hasSize(1)))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(child.toString(), childPass.toString());
		accountHistory("?profileId=" + child).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/me/pass-entry-history")).andExpect(status().isUnauthorized());
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :id").param("id", account).update();
		accountHistory("").andExpect(status().isForbidden());
	}

	private ResultActions accountHistory(String suffix) throws Exception {
		return mvc.perform(get("/api/v1/me/pass-entry-history" + suffix).header("Authorization", "Bearer parent"));
	}

	@Test
	void rejectsBadPagingAndInactiveOrUnauthenticatedAccounts() throws Exception {
		for (String suffix : List.of("?page=-1", "?size=0", "?size=101", "?page=bad")) {
			perform("parent", own, pass, suffix).andExpect(status().isBadRequest());
		}
		mvc.perform(get(path(own, pass))).andExpect(status().isUnauthorized());
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :id").param("id", account).update();
		perform("parent", own, pass, "").andExpect(status().isForbidden());
	}

	private static Map<String, Object> item(String body, UUID reservation) {
		List<Map<String, Object>> items = JsonPath.read(body, "$.items[?(@.reservationId == '" + reservation + "')]");
		assertThat(items).hasSize(1);
		return items.getFirst();
	}

	private UUID account(String name) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@pass-usage.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", "usage-" + name).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", id).update();
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256").subject("usage-" + name).build());
		return id;
	}

	private UUID profile(UUID account, String kind) {
		return jdbc.sql("INSERT INTO customer_profile (account_id, kind, first_name, last_name) VALUES (:account, :kind, 'Usage', 'Tester') RETURNING id")
			.param("account", account, java.sql.Types.OTHER).param("kind", kind).query(UUID.class).single();
	}

	private UUID pass(UUID customer, boolean unlimited) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				 price_paid, status, valid_from, valid_until) VALUES (:profile, :variant, 'Saved pass name', :total, :remaining,
				 100, 'ACTIVE', CURRENT_DATE - 10, CURRENT_DATE + 20) RETURNING id
				""").param("profile", customer).param("variant", variant)
			.param("total", unlimited ? null : 8, java.sql.Types.SMALLINT)
			.param("remaining", unlimited ? null : 5, java.sql.Types.SMALLINT).query(UUID.class).single();
	}

	private UUID reservation(UUID customer, UUID pass, String title, boolean personal) {
		UUID event = jdbc.sql("""
				INSERT INTO schedule_event (kind, visibility, title, starts_at, ends_at)
				VALUES (:kind, :visibility, :title, CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '23 hours') RETURNING id
				""").param("kind", personal ? "PERSONAL_TRAINING" : "CLASS")
			.param("visibility", personal ? "PRIVATE" : "PUBLIC").param("title", title).query(UUID.class).single();
		return jdbc.sql("INSERT INTO reservation (event_id, customer_id, customer_pass_id, booked_by) VALUES (:event, :customer, :pass, :actor) RETURNING id")
			.param("event", event).param("customer", customer).param("pass", pass).param("actor", account).query(UUID.class).single();
	}

	private UUID ledger(UUID pass, UUID reservation, String kind, int delta, int hours) {
		return jdbc.sql("""
				INSERT INTO pass_ledger (pass_id, reservation_id, kind, entries_delta, reason, actor_account_id, occurred_at)
				VALUES (:pass, :reservation, :kind, :delta, 'internal-only', :actor, :time) RETURNING id
				""").param("pass", pass).param("reservation", reservation, java.sql.Types.OTHER).param("kind", kind)
			.param("delta", delta).param("actor", account)
			.param("time", OffsetDateTime.ofInstant(now.plusSeconds(hours * 3600L), ZoneOffset.UTC)).query(UUID.class).single();
	}

	private ResultActions perform(String token, UUID profile, UUID pass, String suffix) throws Exception {
		return mvc.perform(get(path(profile, pass) + suffix).header("Authorization", "Bearer " + token));
	}

	private static String path(UUID profile, UUID pass) {
		return "/api/v1/me/profiles/" + profile + "/passes/" + pass + "/entry-history";
	}

}
