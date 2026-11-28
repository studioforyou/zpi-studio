package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class TrainerSettlementsTests {

	private static final String ROOT = "/api/v1/trainer-settlements";
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@MockitoBean JwtDecoder jwtDecoder;
	private final List<UUID> accounts = new ArrayList<>();
	private final List<UUID> events = new ArrayList<>();
	private UUID admin;
	private UUID staff;
	private UUID completed;

	@BeforeEach
	void setup() {
		admin = account("ADMIN", "settlement-admin");
		UUID trainer = account("TRAINER", "settlement-trainer");
		account("CLIENT", "settlement-client");
		staff =
				jdbc.sql(
								"INSERT INTO staff_profile(account_id,first_name,last_name) VALUES"
									+ " (:id,'Łucja','Żak') RETURNING id")
						.param("id", trainer)
						.query(UUID.class)
						.single();
		completed = event("COMPLETED", "2025-03-10 08:00:00+00", "2025-03-10 09:00:00+00");
		event("SCHEDULED", "2025-03-11 08:00:00+00", "2025-03-11 08:30:00+00");
	}

	@Test
	void savesImmutableHoursAndRequiresExplicitExplainedRefresh() throws Exception {
		UUID id = approve(10000);
		read(id).andExpect(jsonPath("$.summary.version").value(0));
		jdbc.sql("UPDATE schedule_event SET ends_at=ends_at+INTERVAL '1 hour' WHERE id=:id")
				.param("id", completed)
				.update();
		read(id).andExpect(jsonPath("$.summary.countedSeconds").value(3600));
		var correction = correction(0, 15000, null);
		write("/" + id + "/revisions", correction)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.countedSeconds").value(3600));
		String fingerprint =
				field(
						mvc.perform(
								get(ROOT + "/" + id + "/preview")
										.header("Authorization", "Bearer settlement-admin")),
						"$.fingerprint");
		write("/" + id + "/revisions", correction(1, 15000, fingerprint))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.countedSeconds").value(7200))
				.andExpect(jsonPath("$.revisions.length()").value(3))
				.andExpect(jsonPath("$.revisions[2].snapshot.report.totals.seconds").value(5400));
		mvc.perform(
						get(ROOT + "/export?month=2025-03")
								.header("Authorization", "Bearer settlement-admin"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].summary.countedSeconds").value(7200));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void rejectsChangedPreviewAndDuplicateMonthButSafelyReplaysApproval() throws Exception {
		var approval = approval(10000);
		jdbc.sql("UPDATE schedule_event SET title='Zmieniony termin' WHERE id=:id")
				.param("id", completed)
				.update();
		write("", approval)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_SOURCE_CHANGED"));
		assertThat(
						jdbc.sql("SELECT COUNT(*) FROM trainer_settlement WHERE staff_id=:id")
								.param("id", staff)
								.query(Long.class)
								.single())
				.isZero();
		approval = approval(10000);
		write("", approval).andExpect(status().isOk());
		write("", approval)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.revisions.length()").value(1));
		write("", approval(10000))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_EXISTS"));
	}

	@Test
	void countsPartialPayoutsExactlyAndRejectsStaleWritesCurrencyMismatchAndOverpayment()
			throws Exception {
		UUID id = approve(20000);
		var payout = payout(0, 5011);
		write("/" + id + "/payouts", payout)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.status").value("PARTIAL"))
				.andExpect(jsonPath("$.summary.remaining.amountMinor").value(14989));
		write("/" + id + "/payouts", payout)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payments.length()").value(1));
		write("/" + id + "/payouts", payout(0, 1000))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		var wrongCurrency = payout(1, 1000);
		wrongCurrency.put("amount", Map.of("amountMinor", 1000, "currency", "EUR"));
		write("/" + id + "/payouts", wrongCurrency).andExpect(status().isBadRequest());
		write("/" + id + "/payouts", payout(1, 15000)).andExpect(status().isConflict());
		write("/" + id + "/revisions", correction(1, 5000, null)).andExpect(status().isConflict());
		write("/" + id + "/payouts", payout(1, 14989))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.status").value("PAID"));
		write("/" + id + "/payouts", payout)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.paid.amountMinor").value(20000));
		payout.put("amount", money(7000));
		write("/" + id + "/payouts", payout)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SETTLEMENT_OPERATION_CONFLICT"));
	}

	@Test
	void withdrawingAnIncorrectEntryPreservesTheOriginalAndCannotBeRepeated() throws Exception {
		UUID id = approve(10000);
		var payout = payout(0, 6000);
		write("/" + id + "/payouts", payout).andExpect(status().isOk());
		var reversal =
				Map.of(
						"operationId",
						UUID.randomUUID(),
						"version",
						1,
						"reason",
						"Błędna data przelewu");
		String path = "/" + id + "/payouts/" + payout.get("operationId") + "/reversal";
		write(path, reversal)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.paid.amountMinor").value(0))
				.andExpect(jsonPath("$.payments.length()").value(2))
				.andExpect(
						jsonPath("$.payments[?(@.reversed==true)].amount.amountMinor")
								.value(org.hamcrest.Matchers.contains(6000)));
		write(path, reversal).andExpect(status().isOk());
		write(path, Map.of("operationId", UUID.randomUUID(), "version", 2, "reason", "Ponownie"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PAYOUT_ALREADY_REVERSED"));
	}

	@Test
	void validatesMoneyDatesReasonsAndFuturePeriods() throws Exception {
		mvc.perform(get(ROOT + "?month=2099-01").header("Authorization", "Bearer settlement-admin"))
				.andExpect(status().isBadRequest());
		mvc.perform(get(ROOT + "?month=2025-13").header("Authorization", "Bearer settlement-admin"))
				.andExpect(status().isBadRequest());
		UUID id = approve(10000);
		write("/" + id + "/payouts", payout(0, 0)).andExpect(status().isBadRequest());
		var future = payout(0, 5000);
		future.put("paidOn", "2099-01-01");
		write("/" + id + "/payouts", future).andExpect(status().isBadRequest());
		var blank = correction(0, 10000, null);
		blank.put("reason", "  ");
		write("/" + id + "/revisions", blank).andExpect(status().isBadRequest());
		var large = payout(0, 5000);
		large.put("amount", money(10000000000L));
		write("/" + id + "/payouts", large).andExpect(status().isBadRequest());
		var negative = payout(0, -100);
		write("/" + id + "/payouts", negative).andExpect(status().isBadRequest());
		var missingVersion = payout(0, 5000);
		missingVersion.remove("version");
		write("/" + id + "/payouts", missingVersion).andExpect(status().isBadRequest());
		write("/" + id + "/payouts", payout(-1, 5000)).andExpect(status().isBadRequest());
	}

	@Test
	void onlyAdministratorsCanReadApproveOrRecordPayouts() throws Exception {
		mvc.perform(get(ROOT + "?month=2025-03")).andExpect(status().isUnauthorized());
		for (String role : List.of("settlement-client", "settlement-trainer")) {
			mvc.perform(get(ROOT + "?month=2025-03").header("Authorization", "Bearer " + role))
					.andExpect(status().isForbidden());
			mvc.perform(
							post(ROOT)
									.header("Authorization", "Bearer " + role)
									.contentType("application/json")
									.content(json.writeValueAsString(approval(10000))))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void rejectsFractionalMinorUnitsRatherThanRoundingThePayout() throws Exception {
		UUID id = approve(10000);
		var request = payout(0, 2500);
		request.put("amount", Map.of("amountMinor", new java.math.BigDecimal("2500.5"), "currency", "PLN"));
		write("/" + id + "/payouts", request).andExpect(status().isBadRequest());
	}

	@Test
	void refreshedCorrectionsKeepTheirCurrencyAndCanBeSafelyRetried() throws Exception {
		UUID id = approve(10000);
		int updated = jdbc.sql("UPDATE studio SET currency='EUR'").update();
		if (updated == 0) jdbc.sql("INSERT INTO studio(name,time_zone,currency) VALUES ('Studio','Europe/Warsaw','EUR')").update();
		var preview = mvc.perform(get(ROOT + "/" + id + "/preview").header("Authorization", "Bearer settlement-admin"));
		preview.andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("PLN"));
		var request = correction(0, 15000, field(preview, "$.fingerprint"));
		write("/" + id + "/revisions", request).andExpect(status().isOk());
		write("/" + id + "/revisions", request).andExpect(status().isOk()).andExpect(jsonPath("$.revisions.length()").value(2));
		request.put("fingerprint", null);
		write("/" + id + "/revisions", request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTLEMENT_OPERATION_CONFLICT"));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void serializesConcurrentPayoutsAndRetriesWithoutDuplicates() throws Exception {
		UUID id = approve(20000);
		var same = payout(0, 5000);
		assertThat(concurrent(id, same, same)).containsExactlyInAnyOrder(200, 200);
		read(id).andExpect(jsonPath("$.summary.paid.amountMinor").value(5000))
				.andExpect(jsonPath("$.payments.length()").value(1));
		assertThat(concurrent(id, payout(1, 5000), payout(1, 5000)))
				.containsExactlyInAnyOrder(200, 409);
		read(id).andExpect(jsonPath("$.summary.paid.amountMinor").value(10000))
				.andExpect(jsonPath("$.payments.length()").value(2));
	}

	private List<Integer> concurrent(UUID id, Map<String, Object> first, Map<String, Object> second)
			throws Exception {
		try (var threads = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var a =
					threads.submit(
							() -> {
								start.await();
								return write("/" + id + "/payouts", first)
										.andReturn()
										.getResponse()
										.getStatus();
							});
			var b =
					threads.submit(
							() -> {
								start.await();
								return write("/" + id + "/payouts", second)
										.andReturn()
										.getResponse()
										.getStatus();
							});
			start.countDown();
			return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
		}
	}

	@AfterEach
	void removeCommittedConcurrencyFixtures() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) return;
		jdbc.sql(
						"DELETE FROM trainer_payout WHERE settlement_id IN (SELECT id FROM"
							+ " trainer_settlement WHERE staff_id=:id)")
				.param("id", staff)
				.update();
		jdbc.sql(
						"DELETE FROM trainer_settlement_revision WHERE settlement_id IN (SELECT id"
							+ " FROM trainer_settlement WHERE staff_id=:id)")
				.param("id", staff)
				.update();
		jdbc.sql("DELETE FROM trainer_settlement WHERE staff_id=:id").param("id", staff).update();
		jdbc.sql("DELETE FROM event_staff WHERE event_id IN (:ids)").param("ids", events).update();
		jdbc.sql("DELETE FROM schedule_event WHERE id IN (:ids)").param("ids", events).update();
		jdbc.sql("DELETE FROM staff_profile WHERE id=:id").param("id", staff).update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (:ids)")
				.param("ids", accounts)
				.update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (:ids)")
				.param("ids", accounts)
				.update();
		jdbc.sql("DELETE FROM account WHERE id IN (:ids)").param("ids", accounts).update();
	}

	private Map<String, Object> approval(long amount) throws Exception {
		var body = new HashMap<String, Object>();
		body.put("operationId", UUID.randomUUID());
		body.put("staffId", staff);
		body.put("month", "2025-03");
		body.put("amount", money(amount));
		body.put("includeUnconfirmed", false);
		body.put("note", "Uzgodniona kwota");
		body.put(
				"fingerprint",
				field(
						mvc.perform(
								get(ROOT + "/preview?month=2025-03&staffId=" + staff)
										.header("Authorization", "Bearer settlement-admin")),
						"$.fingerprint"));
		return body;
	}

	private UUID approve(long amount) throws Exception {
		var body = approval(amount);
		write("", body).andExpect(status().isOk());
		return (UUID) body.get("operationId");
	}

	private Map<String, Object> correction(long version, long amount, String fingerprint) {
		var body = new HashMap<String, Object>();
		body.put("operationId", UUID.randomUUID());
		body.put("version", version);
		body.put("amount", money(amount));
		body.put("includeUnconfirmed", false);
		body.put("reason", "Korekta uzgodnionej kwoty");
		body.put("fingerprint", fingerprint);
		return body;
	}

	private Map<String, Object> payout(long version, long amount) {
		return new HashMap<>(
				Map.of(
						"operationId",
						UUID.randomUUID(),
						"version",
						version,
						"amount",
						money(amount),
						"paidOn",
						LocalDate.now(ZoneId.of("Europe/Warsaw")).toString(),
						"method",
						"TRANSFER",
						"note",
						"Pierwsza część"));
	}

	private Map<String, Object> money(long amount) {
		return Map.of("amountMinor", amount, "currency", "PLN");
	}

	private ResultActions write(String path, Object body) throws Exception {
		return mvc.perform(
				post(ROOT + path)
						.header("Authorization", "Bearer settlement-admin")
						.contentType("application/json")
						.content(json.writeValueAsString(body)));
	}

	private ResultActions read(UUID id) throws Exception {
		return mvc.perform(get(ROOT + "/" + id).header("Authorization", "Bearer settlement-admin"))
				.andExpect(status().isOk());
	}

	private String field(ResultActions result, String path) throws Exception {
		return JsonPath.read(
				result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				path);
	}

	private UUID account(String role, String token) {
		String uid = UUID.randomUUID().toString();
		UUID id =
				jdbc.sql("INSERT INTO account(email,status) VALUES (:mail,'ACTIVE') RETURNING id")
						.param("mail", uid + "@settlements.example.com")
						.query(UUID.class)
						.single();
		accounts.add(id);
		jdbc.sql(
						"INSERT INTO account_role(account_id,role_id) SELECT :id,id FROM role WHERE"
							+ " code=:role")
				.param("id", id)
				.param("role", role)
				.update();
		jdbc.sql(
						"INSERT INTO identity_link(account_id,provider,external_uid) VALUES"
							+ " (:id,'FIREBASE',:uid)")
				.param("id", id)
				.param("uid", uid)
				.update();
		given(jwtDecoder.decode(token))
				.willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID event(String status, String starts, String ends) {
		UUID id =
				jdbc.sql(
								"INSERT INTO"
									+ " schedule_event(kind,title,starts_at,ends_at,status,visibility,created_by)"
									+ " VALUES ('CONSULTATION','Konsultacja',CAST(:starts AS"
									+ " TIMESTAMPTZ),CAST(:ends AS"
									+ " TIMESTAMPTZ),:status,'PRIVATE',:admin) RETURNING id")
						.param("starts", starts)
						.param("ends", ends)
						.param("status", status)
						.param("admin", admin)
						.query(UUID.class)
						.single();
		events.add(id);
		jdbc.sql("INSERT INTO event_staff(event_id,staff_id,role) VALUES (:id,:staff,'LEAD')")
				.param("id", id)
				.param("staff", staff)
				.update();
		return id;
	}
}
