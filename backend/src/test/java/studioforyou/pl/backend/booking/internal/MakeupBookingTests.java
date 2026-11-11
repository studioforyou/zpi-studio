package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.shared.api.ApiException;

/** Real ownership checks, accounting, cancellation and competing PostgreSQL transactions. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MakeupBookingTests {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcClient jdbc;
	@Autowired private GroupClassBookingService bookings;
	@Autowired private StaffBookingService staffBookings;
	@MockitoBean private JwtDecoder decoder;
	private UUID actor, otherActor, profile, child, other, product, variant, policy, service, pass, credit, event;
	private Instant start;

	@BeforeEach
	void setUp() {
		actor = account("parent"); otherActor = account("other");
		profile = customer(actor, "ADULT"); child = customer(null, "CHILD"); other = customer(otherActor, "ADULT");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", profile).param("child", child).update();
		service = jdbc.sql("INSERT INTO service_type (name, category, default_duration_minutes, max_participants) VALUES ('makeup-test', 'GROUP_CLASS', 60, 12) RETURNING id")
			.query(UUID.class).single();
		policy = jdbc.sql("INSERT INTO makeup_policy (name, validity_days, expiry_checked_against) VALUES ('makeup-test', 30, 'EVENT_DATE') RETURNING id").query(UUID.class).single();
		product = jdbc.sql("INSERT INTO pass_product (name, kind, audience, makeup_policy_id) VALUES ('makeup-test', 'ENTRY_PASS', 'ANY', :policy) RETURNING id")
			.param("policy", policy).query(UUID.class).single();
		variant = jdbc.sql("INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price) VALUES (:product, 4, 30, 100) RETURNING id")
			.param("product", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)").param("product", product).param("service", service).update();
		pass = expiredPass(profile); credit = credit(profile, pass);
		start = Instant.now().plusSeconds(3 * 86400);
		event = event(start);
	}

	@AfterEach
	void cleanup() {
		jdbc.sql("UPDATE makeup_credit SET used_reservation_id = NULL WHERE source_pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :v)").param("v", variant).update();
		jdbc.sql("DELETE FROM waitlist_entry WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :s)").param("s", service).update();
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :v)").param("v", variant).update();
		jdbc.sql("DELETE FROM audit_log WHERE entity_id IN (SELECT id FROM reservation WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :s))").param("s", service).update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :s)").param("s", service).update();
		jdbc.sql("DELETE FROM makeup_credit WHERE source_pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :v)").param("v", variant).update();
		jdbc.sql("DELETE FROM customer_pass WHERE variant_id = :v").param("v", variant).update();
		jdbc.sql("DELETE FROM booking_policy WHERE service_type_id = :s OR event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :s)").param("s", service).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :s").param("s", service).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :p").param("p", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE id = :v").param("v", variant).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :p").param("p", product).update();
		jdbc.sql("DELETE FROM makeup_policy WHERE id = :p").param("p", policy).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :s").param("s", service).update();
		jdbc.sql("DELETE FROM guardianship WHERE guardian_id = :p").param("p", profile).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)").param("ids", List.of(profile, child, other)).update();
		for (String table : List.of("account_role", "identity_link")) {
			jdbc.sql("DELETE FROM " + table + " WHERE account_id IN (:ids)").param("ids", List.of(actor, otherActor)).update();
		}
		jdbc.sql("DELETE FROM account WHERE id IN (:ids)").param("ids", List.of(actor, otherActor)).update();
	}

	@Test
	void booksFromAnExpiredExhaustedPassWithoutChangingItsLedgerAndRetriesOnce() throws Exception {
		jdbc.sql("UPDATE pass_product SET is_active = FALSE WHERE id = :id").param("id", product).update();
		offer(profile, credit).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].booking.allowed").value(true));
		String body = book(profile, credit, event).andExpect(status().isCreated())
			.andExpect(jsonPath("$.makeupCreditId").value(credit.toString())).andExpect(jsonPath("$.customerPassId").isEmpty())
			.andReturn().getResponse().getContentAsString();
		String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
		book(profile, credit, event).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
		assertThat(balance()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM pass_ledger WHERE pass_id = :id").param("id", pass).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single()).isEqualTo("USED");
		assertThat(bookings.list(actor, profile, start.minusSeconds(1), start.plusSeconds(7200), true)).hasSize(1);
	}

	@Test
	void rejectsForeignProfilesAndCreditsAndHonoursCurrentGuardianship() throws Exception {
		UUID childCredit = credit(child, expiredPass(child));
		offer(child, childCredit).andExpect(status().isOk());
		book(child, childCredit, event).andExpect(status().isCreated());
		offer(profile, childCredit).andExpect(status().isNotFound());
		offer(other, credit).andExpect(status().isNotFound());
		jdbc.sql("UPDATE guardianship SET ended_at = CURRENT_TIMESTAMP WHERE child_id = :id").param("id", child).update();
		offer(child, childCredit).andExpect(status().isNotFound());
		mvc.perform(get(path(profile, credit)).param("from", Instant.now().toString()).param("to", start.plusSeconds(7200).toString())).andExpect(status().isUnauthorized());
	}

	@Test
	void availabilityRejectsExpiredCreditsBadFundingDatesPrivateEventsAndAgeRestrictions() throws Exception {
		UUID later = event(start.plusSeconds(20 * 86400));
		offer(profile, credit).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		book(profile, credit, later).andExpect(status().isUnprocessableContent());
		jdbc.sql("UPDATE makeup_policy SET expiry_checked_against = 'BOOK_DATE' WHERE id = :id").param("id", policy).update();
		offer(profile, credit).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
		jdbc.sql("UPDATE schedule_event SET visibility = 'PRIVATE' WHERE id = :id").param("id", later).update();
		book(profile, credit, later).andExpect(status().isNotFound());
		jdbc.sql("UPDATE service_type SET min_age = 99 WHERE id = :id").param("id", service).update();
		book(profile, credit, event).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("AGE_RESTRICTION"));
		jdbc.sql("UPDATE makeup_credit SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id").param("id", credit).update();
		offer(profile, credit).andExpect(status().isConflict());
		book(profile, credit, event).andExpect(status().isConflict());
		assertThat(balance()).isZero();
	}

	@Test
	void cancellationReturnsTheSameCreditWithItsOriginalExpiryAndNeverAddsAPassEntry() throws Exception {
		Instant expiry = jdbc.sql("SELECT expires_at FROM makeup_credit WHERE id = :id").param("id", credit).query(OffsetDateTime.class).single().toInstant();
		var reservation = bookings.bookMakeup(actor, profile, event, credit).reservation();
		assertThat(reservation.cancellation().entryReturned()).isTrue();
		var request = new GroupClassDtos.CancelRequest(reservation.version(), true);
		bookings.cancel(actor, profile, reservation.id(), request);
		bookings.cancel(actor, profile, reservation.id(), request);
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single()).isEqualTo("AVAILABLE");
		assertThat(jdbc.sql("SELECT expires_at FROM makeup_credit WHERE id = :id").param("id", credit).query(OffsetDateTime.class).single().toInstant()).isEqualTo(expiry);
		assertThat(balance()).isZero();
		book(profile, credit, event).andExpect(status().isCreated());
	}

	@Test
	void expiryBetweenPreviewAndCancellationRequiresNewConsentAndNeverRenewsTheCredit() throws Exception {
		var reservation = bookings.bookMakeup(actor, profile, event, credit).reservation();
		jdbc.sql("UPDATE makeup_credit SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id").param("id", credit).update();
		mvc.perform(post("/api/v1/me/profiles/" + profile + "/reservations/" + reservation.id() + "/cancel")
			.header("Authorization", "Bearer parent").contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"expectedEntryReturned\":true}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_CONSEQUENCE_CHANGED"));
		bookings.cancel(actor, profile, reservation.id(), new GroupClassDtos.CancelRequest(0, false));
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single()).isEqualTo("USED");
		assertThat(balance()).isZero();
	}

	@Test
	void lateCancellationConsumesTheCreditWithoutDebitingThePass() {
		var reservation = bookings.bookMakeup(actor, profile, event, credit).reservation();
		jdbc.sql("UPDATE reservation SET free_cancel_until = CURRENT_TIMESTAMP - INTERVAL '1 hour', late_cancel_allowed_snapshot = TRUE, late_cancel_forfeits_entry_snapshot = TRUE WHERE id = :id")
			.param("id", reservation.id()).update();
		bookings.cancel(actor, profile, reservation.id(), new GroupClassDtos.CancelRequest(0, false));
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single()).isEqualTo("USED");
		assertThat(balance()).isZero();
	}

	@Test
	void twoDifferentClassesCannotUseTheSameCreditConcurrently() throws Exception {
		UUID second = event(start.plusSeconds(7200));
		var result = compete(() -> reserve(actor, profile, event, credit), () -> reserve(actor, profile, second, credit));
		assertThat(result).containsExactlyInAnyOrder("OK", "MAKEUP_CREDIT_NOT_AVAILABLE");
		assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE event_id IN (:events)").param("events", List.of(event, second)).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void theLastPlaceHasOneWinnerAndTheOtherCreditStaysAvailable() throws Exception {
		UUID otherCredit = credit(other, expiredPass(other));
		jdbc.sql("UPDATE schedule_event SET capacity = 1 WHERE id = :id").param("id", event).update();
		var result = compete(() -> reserve(actor, profile, event, credit), () -> reserve(otherActor, other, event, otherCredit));
		assertThat(result).containsExactlyInAnyOrder("OK", "CLASS_FULL");
		assertThat(jdbc.sql("SELECT count(*) FROM makeup_credit WHERE id IN (:ids) AND status = 'AVAILABLE'").param("ids", List.of(credit, otherCredit)).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void aPlaceHeldForAnotherWaitingParticipantCannotBeBookedWithAMakeupCredit() throws Exception {
		jdbc.sql("UPDATE schedule_event SET capacity = 1 WHERE id = :id").param("id", event).update();
		jdbc.sql("INSERT INTO waitlist_entry (event_id, customer_id, added_by, status, offer_expires_at) VALUES (:event, :customer, :actor, 'OFFERED', CURRENT_TIMESTAMP + INTERVAL '1 hour')")
			.param("event", event).param("customer", other).param("actor", otherActor).update();
		offer(profile, credit).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].availablePlaces").value(0))
			.andExpect(jsonPath("$.items[0].booking.reason").value("CLASS_FULL"));
		book(profile, credit, event).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASS_FULL"));
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single()).isEqualTo("AVAILABLE");
	}

	@Test
	void cancellingAMakeupBookingReturnsItsCreditAndPromotesTheWaitingParticipant() {
		jdbc.sql("UPDATE schedule_event SET capacity = 1 WHERE id = :id").param("id", event).update();
		jdbc.sql("INSERT INTO booking_policy (scope, event_id, waitlist_enabled, waitlist_confirm_minutes) VALUES ('EVENT', :event, TRUE, 0)")
			.param("event", event).update();
		var reservation = bookings.bookMakeup(actor, profile, event, credit).reservation();
		UUID funding = expiredPass(other);
		jdbc.sql("UPDATE customer_pass SET status = 'ACTIVE', entries_remaining = 4, valid_from = CURRENT_DATE, valid_until = CURRENT_DATE + 30 WHERE id = :id")
			.param("id", funding).update();
		jdbc.sql("INSERT INTO waitlist_entry (event_id, customer_id, added_by, customer_pass_id) VALUES (:event, :customer, :actor, :pass)")
			.param("event", event).param("customer", other).param("actor", otherActor).param("pass", funding).update();
		bookings.cancel(actor, profile, reservation.id(), new GroupClassDtos.CancelRequest(0, true));
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single()).isEqualTo("AVAILABLE");
		assertThat(jdbc.sql("SELECT status FROM waitlist_entry WHERE event_id = :id").param("id", event).query(String.class).single()).isEqualTo("PROMOTED");
		assertThat(jdbc.sql("SELECT entries_remaining::INT FROM customer_pass WHERE id = :id").param("id", funding).query(Integer.class).single()).isEqualTo(3);
		assertThat(balance()).isZero();
	}

	@Test
	void administratorCancellationReturnsTheOriginalCreditWithoutRenewingIt() {
		var reservation = bookings.bookMakeup(actor, profile, event, credit).reservation();
		Instant expiry = jdbc.sql("SELECT expires_at FROM makeup_credit WHERE id = :id").param("id", credit).query(OffsetDateTime.class).single().toInstant();
		var result = staffBookings.cancel(actor, event, reservation.id(), new StaffBookingDtos.StaffCancelRequest(0, true, null));
		assertThat(result.entryReturned()).isTrue();
		assertThat(result.makeupCreditId()).isEqualTo(credit);
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", credit).query(String.class).single()).isEqualTo("AVAILABLE");
		assertThat(jdbc.sql("SELECT expires_at FROM makeup_credit WHERE id = :id").param("id", credit).query(OffsetDateTime.class).single().toInstant()).isEqualTo(expiry);
		assertThat(balance()).isZero();
	}

	private String reserve(UUID account, UUID customer, UUID occurrence, UUID funding) {
		try { bookings.bookMakeup(account, customer, occurrence, funding); return "OK"; }
		catch (ApiException exception) { return exception.getErrorCode().name(); }
	}

	private List<String> compete(Callable<String> first, Callable<String> second) throws Exception {
		try (var pool = Executors.newFixedThreadPool(2)) {
			var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
			var a = pool.submit(() -> { ready.countDown(); go.await(); return first.call(); });
			var b = pool.submit(() -> { ready.countDown(); go.await(); return second.call(); });
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
			return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
		}
	}

	private UUID account(String name) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id").param("email", "makeup-" + name + "@booking.example").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)").param("id", id).param("uid", "makeup-" + name).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'").param("id", id).update();
		given(decoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256").subject("makeup-" + name).build());
		return id;
	}

	private UUID customer(UUID account, String kind) {
		return jdbc.sql("INSERT INTO customer_profile (account_id, kind, first_name, last_name, birth_date) VALUES (:account, :kind, 'Makeup', 'Tester', '2016-01-01') RETURNING id")
			.param("account", account, java.sql.Types.OTHER).param("kind", kind).query(UUID.class).single();
	}

	private UUID expiredPass(UUID owner) {
		return jdbc.sql("INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining, price_paid, status, valid_from, valid_until) VALUES (:owner, :variant, 'Old pass', 4, 0, 100, 'EXPIRED', CURRENT_DATE - 40, CURRENT_DATE - 10) RETURNING id")
			.param("owner", owner).param("variant", variant).query(UUID.class).single();
	}

	private UUID credit(UUID owner, UUID sourcePass) {
		return jdbc.sql("INSERT INTO makeup_credit (customer_id, source_pass_id, expires_at) VALUES (:owner, :pass, CURRENT_TIMESTAMP + INTERVAL '10 days') RETURNING id")
			.param("owner", owner).param("pass", sourcePass).query(UUID.class).single();
	}

	private UUID event(Instant when) {
		return jdbc.sql("INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility) VALUES ('CLASS', :service, 'Make-up session', :start, :end, 12, 'PUBLIC') RETURNING id")
			.param("service", service).param("start", when.atOffset(ZoneOffset.UTC)).param("end", when.plusSeconds(3600).atOffset(ZoneOffset.UTC)).query(UUID.class).single();
	}

	private int balance() { return jdbc.sql("SELECT entries_remaining::INT FROM customer_pass WHERE id = :id").param("id", pass).query(Integer.class).single(); }
	private static String path(UUID profile, UUID credit) { return "/api/v1/me/profiles/" + profile + "/makeup-credits/" + credit + "/group-classes"; }
	private ResultActions offer(UUID profile, UUID credit) throws Exception { return mvc.perform(get(path(profile, credit)).param("from", Instant.now().toString()).param("to", start.plusSeconds(30 * 86400).toString()).header("Authorization", "Bearer parent")); }
	private ResultActions book(UUID profile, UUID credit, UUID event) throws Exception { return mvc.perform(post(path(profile, credit) + "/" + event + "/reservations").header("Authorization", "Bearer parent")); }
}
