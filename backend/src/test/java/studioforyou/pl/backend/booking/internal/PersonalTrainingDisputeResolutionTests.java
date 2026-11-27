package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.ResolveRequest;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeResolution;
import studioforyou.pl.backend.shared.api.ApiException;

/** Administrators decide on clients' disputes about held personal sessions (#113). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PersonalTrainingDisputeResolutionTests {

	private static final String DISPUTES = "/api/v1/personal-training-disputes";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PersonalTrainingDisputes disputes;

	@MockitoBean
	JwtDecoder decoder;

	UUID client, admin, adminStaff, profile, type, policy, product, variant, pass, event, reservation, dispute;

	@BeforeEach
	void setUp() {
		client = account("dispute-client", "CLIENT");
		admin = account("dispute-admin", "ADMIN");
		adminStaff = jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name, calendar_color, status)
				VALUES (:account, 'Beata', 'Decyzja', '#123456', 'ACTIVE') RETURNING id
				""").param("account", admin).query(UUID.class).single();
		profile = jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, 'ADULT', 'Dora', 'Sporna') RETURNING id
				""").param("account", client).query(UUID.class).single();
		type = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, max_participants)
				VALUES ('pt-disputes', 'PERSONAL_TRAINING', 60, 1) RETURNING id
				""").query(UUID.class).single();
		policy = jdbc.sql("INSERT INTO makeup_policy (name, validity_days, max_per_pass) VALUES ('pt-disputes', 21, 1) RETURNING id")
			.query(UUID.class).single();
		product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience, makeup_policy_id)
				VALUES ('pt-disputes', 'PT_PACKAGE', 'ANY', :policy) RETURNING id
				""").param("policy", policy).query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 60, 500) RETURNING id
				""").param("product", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :type)")
			.param("product", product).param("type", type).update();
		pass = jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				                           price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'Pakiet sporny', 8, 7, 500, 'ACTIVE', CURRENT_DATE - 10, CURRENT_DATE + 50)
				RETURNING id
				""").param("customer", profile).param("variant", variant).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_ledger (pass_id, kind, entries_delta) VALUES (:pass, 'ACTIVATION', 8)")
			.param("pass", pass).update();
		event = heldSession(Instant.now().minus(5, ChronoUnit.HOURS));
		reservation = reservation(event);
		dispute = dispute(reservation, "Trening trwał 30 minut zamiast 60.", Instant.now().minus(2, ChronoUnit.HOURS));
	}

	@AfterEach
	void cleanUp() {
		String sessions = "SELECT id FROM schedule_event WHERE service_type_id = :type";
		String bookings = "SELECT id FROM reservation WHERE event_id IN (" + sessions + ")";
		jdbc.sql("DELETE FROM audit_log WHERE entity_id IN (" + bookings + ")").param("type", type).update();
		jdbc.sql("DELETE FROM training_dispute WHERE reservation_id IN (" + bookings + ")").param("type", type).update();
		jdbc.sql("UPDATE reservation SET makeup_credit_id = NULL WHERE event_id IN (" + sessions + ")").param("type", type).update();
		jdbc.sql("DELETE FROM makeup_credit WHERE customer_id = :profile").param("profile", profile).update();
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id = :pass").param("pass", pass).update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + sessions + ")").param("type", type).update();
		jdbc.sql("DELETE FROM event_staff WHERE staff_id = :staff").param("staff", adminStaff).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :type").param("type", type).update();
		jdbc.sql("DELETE FROM customer_pass WHERE customer_id = :profile").param("profile", profile).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM makeup_policy WHERE id = :policy").param("policy", policy).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :type").param("type", type).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id = :profile").param("profile", profile).update();
		jdbc.sql("DELETE FROM staff_profile WHERE id = :staff").param("staff", adminStaff).update();
		for (String table : List.of("account_role", "identity_link")) {
			jdbc.sql("DELETE FROM " + table + " WHERE account_id IN (:ids)").param("ids", List.of(client, admin)).update();
		}
		jdbc.sql("DELETE FROM account WHERE id IN (:ids)").param("ids", List.of(client, admin)).update();
	}

	@Test
	void onlyAdministratorsSeeDisputesAndAnOpenOneComesWithWhatEachDecisionWouldDo() throws Exception {
		mvc.perform(get(DISPUTES).header("Authorization", "Bearer dispute-client")).andExpect(status().isForbidden());
		mvc.perform(get(DISPUTES)).andExpect(status().isUnauthorized());
		String ids = admin(get(DISPUTES).param("size", "100")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.<List<String>>read(ids, "$.items[*].id")).contains(dispute.toString());
		detail().andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.reason").value("Trening trwał 30 minut zamiast 60."))
			.andExpect(jsonPath("$.raisedByName").value("Dora Sporna"))
			.andExpect(jsonPath("$.customerName").value("Dora Sporna"))
			.andExpect(jsonPath("$.customerAccountId").value(client.toString()))
			.andExpect(jsonPath("$.attendance").value("PRESENT"))
			.andExpect(jsonPath("$.options.funding").value("PASS"))
			.andExpect(jsonPath("$.options.passName").value("Pakiet sporny"))
			.andExpect(jsonPath("$.options.entriesRemaining").value(7))
			.andExpect(jsonPath("$.options.upheld.allowed").value(true))
			.andExpect(jsonPath("$.options.upheld.effect").value("ENTRY_RETURNED"))
			.andExpect(jsonPath("$.options.upheld.notice").doesNotExist())
			.andExpect(jsonPath("$.options.upheldMakeup.allowed").value(true))
			.andExpect(jsonPath("$.options.upheldMakeup.effect").value("MAKEUP_GRANTED"))
			.andExpect(jsonPath("$.options.rejected.effect").value("NO_CHANGE"))
			.andExpect(jsonPath("$.options.makeupValidityDays").value(21));
	}

	@Test
	void upholdingGivesTheUsedEntryBackOnceAndSettlesTheSessionForTheClient() throws Exception {
		resolve("UPHELD", "  Trener potwierdził skrócenie zajęć.  ", null, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RESOLVED"))
			.andExpect(jsonPath("$.resolution").value("UPHELD"))
			.andExpect(jsonPath("$.resolutionEffect").value("ENTRY_RETURNED"))
			.andExpect(jsonPath("$.resolutionNote").value("Trener potwierdził skrócenie zajęć."))
			.andExpect(jsonPath("$.resolvedByName").value("Beata Decyzja"))
			.andExpect(jsonPath("$.options").doesNotExist());
		assertThat(balance()).isEqualTo(8);
		assertThat(ledger("ENTRY_CONSUMED")).isEqualTo(1);
		assertThat(ledger("ENTRY_RETURNED")).isZero();
		assertThat(jdbc.sql("""
				SELECT entries_delta::INT || ' ' || reason FROM pass_ledger
				WHERE pass_id = :pass AND reservation_id = :reservation AND kind = 'CORRECTION'
				""").param("pass", pass).param("reservation", reservation).query(String.class).single())
			.isEqualTo("1 Trener potwierdził skrócenie zajęć.");
		assertThat(jdbc.sql("SELECT settlement_status FROM reservation WHERE id = :id").param("id", reservation)
			.query(String.class).single()).isEqualTo("FINAL");
		assertThat(jdbc.sql("""
				SELECT count(*) FROM audit_log WHERE entity_id = :id AND action = 'PERSONAL_TRAINING_DISPUTE_RESOLVED'
				AND reason = 'Trener potwierdził skrócenie zajęć.'
				""").param("id", reservation).query(Long.class).single()).isEqualTo(1);
		mvc.perform(get("/api/v1/me/profiles/" + profile + "/personal-trainings/" + reservation)
			.header("Authorization", "Bearer dispute-client")).andExpect(status().isOk())
			.andExpect(jsonPath("$.settlementStatus").value("FINAL"))
			.andExpect(jsonPath("$.dispute.status").value("RESOLVED"))
			.andExpect(jsonPath("$.dispute.resolution").value("UPHELD"))
			.andExpect(jsonPath("$.dispute.resolutionEffect").value("ENTRY_RETURNED"))
			.andExpect(jsonPath("$.dispute.resolutionNote").value("Trener potwierdził skrócenie zajęć."))
			.andExpect(jsonPath("$.disputeAvailability.reason").value("DISPUTE_RESOLVED"));

		resolve("UPHELD", "Jeszcze raz", null, 2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_RESOLVED"));
		assertThat(balance()).isEqualTo(8);
	}

	@Test
	void theEntryHistoryShowsTheUsedEntryAndTheRefundRatherThanAnInconsistency() throws Exception {
		resolve("UPHELD", "Zwrot wejścia", null, 1).andExpect(status().isOk());
		String history = mvc.perform(get("/api/v1/me/profiles/" + profile + "/passes/" + pass + "/entry-history")
			.header("Authorization", "Bearer dispute-client")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.<List<String>>read(history, "$.items[*].kind"))
			.containsExactlyInAnyOrder("CONSUMED", "CORRECTION");
	}

	@Test
	void upholdingWithAMakeupGrantsANewCreditAndKeepsTheEntryUsed() throws Exception {
		resolve("UPHELD_MAKEUP", "Odróbka zamiast zwrotu", null, 1).andExpect(status().isBadRequest());
		resolve("UPHELD_MAKEUP", "Odróbka zamiast zwrotu", 400, 1).andExpect(status().isBadRequest());
		resolve("UPHELD_MAKEUP", "Odróbka zamiast zwrotu", 14, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.resolutionEffect").value("MAKEUP_GRANTED"));
		assertThat(balance()).isEqualTo(7);
		assertThat(ledger("ENTRY_CONSUMED")).isEqualTo(1);
		assertThat(ledger("CORRECTION")).isZero();
		var credit = jdbc.sql("""
				SELECT status, source_pass_id, granted_by, expires_at FROM makeup_credit WHERE source_reservation_id = :id
				""").param("id", reservation).query().singleRow();
		assertThat(credit.get("status")).isEqualTo("AVAILABLE");
		assertThat(credit.get("source_pass_id")).isEqualTo(pass);
		assertThat(credit.get("granted_by")).isEqualTo(admin);
		assertThat(((java.sql.Timestamp) credit.get("expires_at")).toInstant())
			.isCloseTo(Instant.now().plus(14, ChronoUnit.DAYS), within(5, ChronoUnit.MINUTES));
	}

	@Test
	void rejectingKeepsTheEntryUsedAndExplainsItToTheClient() throws Exception {
		resolve("REJECTED", "Trening odbył się zgodnie z grafikiem.", null, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.resolution").value("REJECTED"))
			.andExpect(jsonPath("$.resolutionEffect").value("NO_CHANGE"));
		assertThat(balance()).isEqualTo(7);
		assertThat(ledger("ENTRY_CONSUMED")).isEqualTo(1);
		assertThat(ledger("CORRECTION")).isZero();
		assertThat(credits()).isZero();
		admin(get(DISPUTES).param("status", "RESOLVED").param("size", "100")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + dispute + "')].resolution").value("REJECTED"));
	}

	@Test
	void theExplanationIsRequiredAndAStaleSessionIsRefused() throws Exception {
		resolve("REJECTED", "   ", null, 1).andExpect(status().isBadRequest());
		resolve("REJECTED", "x".repeat(1001), null, 1).andExpect(status().isBadRequest());
		resolve("REJECTED", "Odrzucone", null, 0).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		assertThat(jdbc.sql("SELECT status FROM training_dispute WHERE id = :id").param("id", dispute)
			.query(String.class).single()).isEqualTo("OPEN");
		assertThat(balance()).isEqualTo(7);
	}

	@Test
	void anEntryAlsoGoesBackToAnExpiredPassAndTheAdministratorIsWarned() throws Exception {
		jdbc.sql("UPDATE customer_pass SET valid_until = CURRENT_DATE - 2 WHERE id = :id").param("id", pass).update();
		detail().andExpect(jsonPath("$.options.upheld.allowed").value(true))
			.andExpect(jsonPath("$.options.upheld.notice").value("PASS_EXPIRED"));
		resolve("UPHELD", "Zwrot mimo wygaśnięcia", null, 1).andExpect(status().isOk());
		assertThat(balance()).isEqualTo(8);
	}

	@Test
	void anUnlimitedPassHasNothingToReturnAndGrantsNoMakeup() throws Exception {
		jdbc.sql("UPDATE customer_pass SET entries_total = NULL, entries_remaining = NULL WHERE id = :id").param("id", pass).update();
		jdbc.sql("UPDATE pass_ledger SET entries_delta = 0 WHERE pass_id = :pass").param("pass", pass).update();
		detail().andExpect(jsonPath("$.options.funding").value("UNLIMITED_PASS"))
			.andExpect(jsonPath("$.options.upheld.effect").value("NO_CHANGE"))
			.andExpect(jsonPath("$.options.upheld.notice").value("UNLIMITED_PASS"))
			.andExpect(jsonPath("$.options.upheldMakeup.allowed").value(false))
			.andExpect(jsonPath("$.options.makeupValidityDays").doesNotExist());
		resolve("UPHELD_MAKEUP", "Odróbka", 14, 1).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("DISPUTE_RESOLUTION_UNAVAILABLE"))
			.andExpect(jsonPath("$.notice").value("UNLIMITED_PASS"));
		resolve("UPHELD", "Uznane, bez zmian w saldzie", null, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.resolutionEffect").value("NO_CHANGE"));
		assertThat(credits()).isZero();
	}

	@Test
	void aSessionPaidOutsideTheApplicationOnlyGetsTheDecision() throws Exception {
		jdbc.sql("DELETE FROM pass_ledger WHERE reservation_id = :id").param("id", reservation).update();
		jdbc.sql("UPDATE reservation SET customer_pass_id = NULL WHERE id = :id").param("id", reservation).update();
		detail().andExpect(jsonPath("$.options.funding").value("NONE"))
			.andExpect(jsonPath("$.options.upheld.notice").value("NOT_FUNDED"))
			.andExpect(jsonPath("$.options.upheldMakeup.allowed").value(false));
		resolve("UPHELD", "Zwrot płatności na recepcji", null, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.resolutionEffect").value("NO_CHANGE"));
		assertThat(balance()).isEqualTo(7);
	}

	@Test
	void aMakeupThatPaidForTheSessionComesBackWithItsExpiryWhileTheUsedOneStaysLinked() throws Exception {
		Instant expiry = Instant.now().plus(9, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		UUID used = payWithMakeup(expiry);
		detail().andExpect(jsonPath("$.options.funding").value("MAKEUP"))
			.andExpect(jsonPath("$.options.upheld.effect").value("MAKEUP_RETURNED"))
			.andExpect(jsonPath("$.options.upheld.allowed").value(true));
		resolve("UPHELD", "Zwrot odróbki", null, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.resolutionEffect").value("MAKEUP_RETURNED"));
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id = :id").param("id", used).query(String.class).single())
			.isEqualTo("USED");
		var replacement = jdbc.sql("SELECT status, expires_at FROM makeup_credit WHERE source_reservation_id = :id")
			.param("id", reservation).query().singleRow();
		assertThat(replacement.get("status")).isEqualTo("AVAILABLE");
		assertThat(((java.sql.Timestamp) replacement.get("expires_at")).toInstant()).isEqualTo(expiry);
	}

	@Test
	void anExpiredMakeupCannotComeBackButANewOneCanBeGranted() throws Exception {
		payWithMakeup(Instant.now().minus(1, ChronoUnit.DAYS));
		detail().andExpect(jsonPath("$.options.upheld.allowed").value(false))
			.andExpect(jsonPath("$.options.upheld.notice").value("MAKEUP_EXPIRED"))
			.andExpect(jsonPath("$.options.upheldMakeup.allowed").value(true));
		resolve("UPHELD", "Zwrot odróbki", null, 1).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("DISPUTE_RESOLUTION_UNAVAILABLE"));
		resolve("UPHELD_MAKEUP", "Nowa odróbka", 21, 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.resolutionEffect").value("MAKEUP_GRANTED"));
		assertThat(jdbc.sql("SELECT count(*) FROM makeup_credit WHERE source_reservation_id = :id AND status = 'AVAILABLE'")
			.param("id", reservation).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void twoAdministratorsDecidingAtOnceReturnTheEntryOnlyOnce() throws Exception {
		var results = compete(() -> resolveDirect(DisputeResolution.UPHELD), () -> resolveDirect(DisputeResolution.UPHELD));
		assertThat(results).containsExactlyInAnyOrder("OK", "DISPUTE_ALREADY_RESOLVED");
		assertThat(balance()).isEqualTo(8);
		assertThat(ledger("CORRECTION")).isEqualTo(1);
	}

	@Test
	void openDisputesWaitInTheOrderTheyWereReported() throws Exception {
		UUID laterEvent = heldSession(Instant.now().minus(4, ChronoUnit.HOURS));
		UUID later = dispute(reservation(laterEvent), "Brak trenera", Instant.now().minus(1, ChronoUnit.HOURS));
		String page = admin(get(DISPUTES).param("size", "100")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<String> ids = com.jayway.jsonpath.JsonPath.read(page, "$.items[*].id");
		assertThat(ids.indexOf(dispute.toString())).isNotNegative().isLessThan(ids.indexOf(later.toString()));
		admin(get(DISPUTES + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
	}

	private UUID payWithMakeup(Instant expiresAt) {
		jdbc.sql("DELETE FROM pass_ledger WHERE reservation_id = :id").param("id", reservation).update();
		UUID credit = jdbc.sql("""
				INSERT INTO makeup_credit (customer_id, source_pass_id, status, used_at, used_reservation_id, expires_at)
				VALUES (:customer, :pass, 'USED', now() - interval '1 day', :reservation, :expires) RETURNING id
				""").param("customer", profile).param("pass", pass).param("reservation", reservation)
			.param("expires", utc(expiresAt)).query(UUID.class).single();
		jdbc.sql("UPDATE reservation SET customer_pass_id = NULL, makeup_credit_id = :credit WHERE id = :id")
			.param("credit", credit).param("id", reservation).update();
		return credit;
	}

	private UUID heldSession(Instant start) {
		UUID id = jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status)
				VALUES ('PERSONAL_TRAINING', :type, 'Trening sporny', :start, :end, 1, 'PRIVATE', 'COMPLETED') RETURNING id
				""").param("type", type).param("start", utc(start)).param("end", utc(start.plus(1, ChronoUnit.HOURS)))
			.query(UUID.class).single();
		jdbc.sql("INSERT INTO event_staff (event_id, staff_id, role) VALUES (:event, :staff, 'LEAD')")
			.param("event", id).param("staff", adminStaff).update();
		return id;
	}

	/** A held session the trainer confirmed, funded by the pass, whose entry was allocated. */
	private UUID reservation(UUID eventId) {
		UUID id = jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, customer_pass_id, booked_by, attendance,
				                         trainer_confirmed_at, settlement_status, version)
				VALUES (:event, :customer, :pass, :actor, 'PRESENT', now() - interval '3 hours', 'DISPUTED', 1)
				RETURNING id
				""").param("event", eventId).param("customer", profile).param("pass", pass).param("actor", client)
			.query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO pass_ledger (pass_id, reservation_id, kind, entries_delta)
				VALUES (:pass, :reservation, 'ENTRY_RESERVED', -1)
				""").param("pass", pass).param("reservation", id).update();
		return id;
	}

	private UUID dispute(UUID reservationId, String reason, Instant raisedAt) {
		return jdbc.sql("""
				INSERT INTO training_dispute (reservation_id, raised_by, reason, raised_at)
				VALUES (:reservation, :client, :reason, :raisedAt) RETURNING id
				""").param("reservation", reservationId).param("client", client).param("reason", reason)
			.param("raisedAt", utc(raisedAt)).query(UUID.class).single();
	}

	private UUID account(String token, String role) {
		String uid = "pt-disputes-" + UUID.randomUUID();
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", uid + "@example.test").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id).param("role", role).update();
		given(decoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private ResultActions admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
			throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer dispute-admin"));
	}

	private ResultActions detail() throws Exception {
		return admin(get(DISPUTES + "/" + dispute));
	}

	private ResultActions resolve(String resolution, String note, Integer days, int version) throws Exception {
		String body = "{\"resolution\":\"" + resolution + "\",\"note\":\"" + note + "\",\"makeupValidityDays\":" + days
				+ ",\"version\":" + version + "}";
		return admin(post(DISPUTES + "/" + dispute + "/resolve").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String resolveDirect(DisputeResolution resolution) {
		try {
			disputes.resolve(admin, dispute, new ResolveRequest(resolution, "Równoległa decyzja", null, 1));
			return "OK";
		}
		catch (ApiException error) {
			return error.getErrorCode().name();
		}
	}

	private List<String> compete(Callable<String> first, Callable<String> second) throws Exception {
		try (var pool = Executors.newFixedThreadPool(2)) {
			var ready = new CountDownLatch(2);
			var go = new CountDownLatch(1);
			var a = pool.submit(() -> {
				ready.countDown();
				go.await();
				return first.call();
			});
			var b = pool.submit(() -> {
				ready.countDown();
				go.await();
				return second.call();
			});
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
		}
	}

	private int balance() {
		return jdbc.sql("SELECT entries_remaining::INT FROM customer_pass WHERE id = :id").param("id", pass)
			.query(Integer.class).single();
	}

	private long ledger(String kind) {
		return jdbc.sql("SELECT count(*) FROM pass_ledger WHERE pass_id = :pass AND reservation_id = :reservation AND kind = :kind")
			.param("pass", pass).param("reservation", reservation).param("kind", kind).query(Long.class).single();
	}

	private long credits() {
		return jdbc.sql("SELECT count(*) FROM makeup_credit WHERE customer_id = :profile").param("profile", profile)
			.query(Long.class).single();
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
