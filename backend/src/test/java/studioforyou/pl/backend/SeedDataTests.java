package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dev/stage seed ({@code db/seed}) applies on top of all migrations, fills every table
 * and can run again without errors or duplicates. The event publication registry is runtime
 * state of Spring Modulith (rows exist only while a listener has not finished), so it is left out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class SeedDataTests {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void everyTableHasRows() {
		assertThat(rowCounts()).isNotEmpty().allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());
	}

	@Test
	void seedCanRunAgain() throws Exception {
		Map<String, Long> before = rowCounts();

		Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/seed/*.sql");
		assertThat(scripts).isNotEmpty();
		new ResourceDatabasePopulator(scripts).execute(dataSource);

		assertThat(rowCounts()).isEqualTo(before);
	}

	@Test
	@Transactional
	void makeupExampleRepairsTheLegacyOpenLinkWithoutRenewingTheCredit() {
		assertThat(jdbc.sql("""
				SELECT count(*) FROM makeup_credit credit
				JOIN customer_pass pass ON pass.id = credit.source_pass_id
				JOIN pass_product_variant variant ON variant.id = pass.variant_id
				JOIN pass_product product ON product.id = variant.product_id
				WHERE credit.id = '33000000-0000-0000-0000-000000000002'
				  AND pass.customer_id = credit.customer_id AND variant.entries_count = 4
				  AND product.makeup_policy_id IS NOT NULL AND pass.status = 'EXPIRED'
				  AND credit.status = 'AVAILABLE'
				""").query(Integer.class).single()).isEqualTo(1);
		Map<String, Object> before = makeupExample();
		Map<String, Long> counts = rowCounts();
		jdbc.sql("""
				UPDATE makeup_credit SET source_pass_id = '28000000-0000-0000-0000-000000000005'
				WHERE id = '33000000-0000-0000-0000-000000000002'
				""").update();
		runMakeupExample();
		assertThat(makeupExample()).isEqualTo(before);
		runMakeupExample();
		assertThat(makeupExample()).isEqualTo(before);
		assertThat(rowCounts()).isEqualTo(counts);
	}

	@Test
	@Transactional
	void makeupExampleLeavesAnAlreadyUsedCreditUntouched() {
		jdbc.sql("""
				UPDATE makeup_credit SET source_pass_id = '28000000-0000-0000-0000-000000000005',
				  status = 'USED', used_at = CURRENT_TIMESTAMP
				WHERE id = '33000000-0000-0000-0000-000000000002'
				""").update();
		Map<String, Object> before = makeupExample();
		runMakeupExample();
		assertThat(makeupExample()).isEqualTo(before);
	}

	private void runMakeupExample() {
		new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_09_makeup_credits.sql"))
			.execute(dataSource);
	}

	@Test
	@Transactional
	void personalTrainingExamplesBalanceTheirLedgerAndPreserveTesterChanges() {
		String lowPass = "28000000-0000-0000-0000-000000010001";
		String nextEvent = "19000000-0000-0000-0000-000000010004";
		assertThat(jdbc.sql("SELECT sum(entries_delta) FROM pass_ledger WHERE pass_id = CAST(:id AS UUID)")
			.param("id", lowPass).query(Integer.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = CAST(:id AS UUID)")
			.param("id", lowPass).query(Integer.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM reservation
				WHERE id = '30000000-0000-0000-0000-000000010001'
				  AND trainer_confirmed_at IS NOT NULL AND client_confirmed_at IS NOT NULL
				  AND settlement_status = 'FINAL'
				""").query(Integer.class).single()).isEqualTo(1);
		Map<String, Object> originalCredit = makeupExample();
		Map<String, Long> counts = rowCounts();
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 2, version = version + 1 WHERE id = CAST(:id AS UUID)")
			.param("id", lowPass).update();
		jdbc.sql("""
				UPDATE schedule_event SET title = 'Termin uzgodniony przez testera',
				  starts_at = starts_at + INTERVAL '2 hours', ends_at = ends_at + INTERVAL '2 hours',
				  version = version + 1 WHERE id = CAST(:id AS UUID)
				""").param("id", nextEvent).update();
		Map<String, Object> changedPass = jdbc.sql("SELECT * FROM customer_pass WHERE id = CAST(:id AS UUID)")
			.param("id", lowPass).query().singleRow();
		Map<String, Object> changedEvent = jdbc.sql("SELECT * FROM schedule_event WHERE id = CAST(:id AS UUID)")
			.param("id", nextEvent).query().singleRow();
		jdbc.sql("UPDATE event_staff SET staff_id = '08000000-0000-0000-0000-000000000003' WHERE event_id = CAST(:id AS UUID)")
			.param("id", nextEvent).update();
		jdbc.sql("UPDATE event_resource SET resource_id = '15000000-0000-0000-0000-000000000003' WHERE event_id = CAST(:id AS UUID)")
			.param("id", nextEvent).update();

		Resource script = new ClassPathResource("db/seed/R__seed_10_personal_training.sql");
		new ResourceDatabasePopulator(script).execute(dataSource);
		new ResourceDatabasePopulator(script).execute(dataSource);

		assertThat(jdbc.sql("SELECT * FROM customer_pass WHERE id = CAST(:id AS UUID)")
			.param("id", lowPass).query().singleRow()).isEqualTo(changedPass);
		assertThat(jdbc.sql("SELECT * FROM schedule_event WHERE id = CAST(:id AS UUID)")
			.param("id", nextEvent).query().singleRow()).isEqualTo(changedEvent);
		assertThat(jdbc.sql("SELECT staff_id::TEXT FROM event_staff WHERE event_id = CAST(:id AS UUID)")
			.param("id", nextEvent).query(String.class).list())
			.containsExactly("08000000-0000-0000-0000-000000000003");
		assertThat(jdbc.sql("SELECT resource_id::TEXT FROM event_resource WHERE event_id = CAST(:id AS UUID)")
			.param("id", nextEvent).query(String.class).list())
			.containsExactly("15000000-0000-0000-0000-000000000003");
		assertThat(makeupExample()).isEqualTo(originalCredit);
		assertThat(rowCounts()).isEqualTo(counts);
	}

	@Test
	void seededTrainersCanReadTheirTraineesAndFindTheirPersonalSessionsInTheMainSchedule() throws Exception {
		for (int accountNo : List.of(1, 2, 3)) {
			String token = seedToken(accountNo);
			String summary = seedGet(token, "/api/v1/me/trainees/overview", false);
			assertThat(JsonPath.<List<String>>read(summary, "$.items[*].customerId")).isNotEmpty();
			String schedule = seedGet(token, "/api/v1/me/schedule-events", true);
			Set<String> personalIds = Set.copyOf(JsonPath.read(schedule, "$.items[?(@.kind == 'PERSONAL_TRAINING')].id"));
			assertThat(personalIds).isNotEmpty();
			assertThat(JsonPath.<List<String>>read(schedule, "$.items[?(@.kind == 'CLASS')].id")).isNotEmpty();
			String personal = seedGet(token, "/api/v1/me/trainees/personal-trainings", true);
			List<String> enrichedIds = JsonPath.read(personal, "$.items[*].id");
			assertThat(enrichedIds).isNotEmpty().allMatch(personalIds::contains);
			Set<String> traineeIds = Set.copyOf(JsonPath.read(summary, "$.items[*].customerId"));
			assertThat(JsonPath.<List<String>>read(personal, "$.items[*].customerId")).allMatch(traineeIds::contains);
			if (accountNo == 1) {
				assertThat(personalIds).contains("19000000-0000-0000-0000-000000010020");
				assertThat(enrichedIds).doesNotContain("19000000-0000-0000-0000-000000010020",
						"19000000-0000-0000-0000-000000010004", "19000000-0000-0000-0000-000000010010");
			}
		}
	}

	@Test
	void annaSeedExposesPackagesMakeupsCashSessionsAndMissingTrainerConfirmation() throws Exception {
		String token = seedToken(1);
		String piotr = seedGet(token, "/api/v1/me/trainees/07000000-0000-0000-0000-000000000005/overview", false);
		assertThat(JsonPath.<List<Integer>>read(piotr, "$.passes[?(@.id == '28000000-0000-0000-0000-000000010006')].entriesRemaining"))
			.containsExactly(3);
		assertThat(JsonPath.<List<Boolean>>read(piotr, "$.passes[?(@.id == '28000000-0000-0000-0000-000000010006')].lowBalance"))
			.containsExactly(true);
		String julia = seedGet(token, "/api/v1/me/trainees/07000000-0000-0000-0000-000000000008/overview", false);
		assertThat(JsonPath.<String>read(julia, "$.availability")).isEqualTo("UNAVAILABLE");
		assertThat(JsonPath.<List<String>>read(julia, "$.passes[*].status")).contains("EXPIRED");
		String adam = seedGet(token, "/api/v1/me/trainees/07000000-0000-0000-0000-000000000006/overview", false);
		assertThat(JsonPath.<List<Object>>read(adam, "$.passes")).isEmpty();
		assertThat(JsonPath.<Object>read(adam, "$.nextTraining")).isNull();
		String personal = seedGet(token, "/api/v1/me/trainees/personal-trainings", true);
		assertThat(JsonPath.<List<String>>read(personal, "$.items[*].funding"))
			.contains("PASS_CONSUMED", "PASS_RESERVED", "PASS_RETURNED", "MAKEUP_USED", "NO_PASS")
			.doesNotContain("INCONSISTENT");
		List<Map<String, Object>> waiting = JsonPath.read(personal,
				"$.items[?(@.reservationId == '30000000-0000-0000-0000-000000010012')]");
		assertThat(waiting).hasSize(1);
		assertThat(waiting.getFirst()).containsEntry("trainerConfirmedAt", null).containsEntry("requiresAttention", true);
		assertThat(waiting.getFirst().get("clientConfirmedAt")).isNotNull();
	}

	@Test
	@Transactional
	void extendedPersonalSeedBalancesPackagesAndPreservesChangedRelationshipsAndCredits() {
		assertThat(jdbc.sql("""
				SELECT pass.entries_remaining::INT AS remaining, sum(ledger.entries_delta)::INT AS balance
				FROM customer_pass pass JOIN pass_ledger ledger ON ledger.pass_id = pass.id
				WHERE pass.id BETWEEN '28000000-0000-0000-0000-000000010006' AND '28000000-0000-0000-0000-000000010008'
				GROUP BY pass.id
				""").query().listOfRows()).hasSize(3).allSatisfy(row ->
				assertThat(row.get("remaining")).isEqualTo(row.get("balance")));
		Map<String, Long> counts = rowCounts();
		jdbc.sql("UPDATE staff_assignment SET ended_at = CURRENT_TIMESTAMP WHERE id = '09000000-0000-0000-0000-000000010004'")
			.update();
		jdbc.sql("UPDATE makeup_credit SET expires_at = expires_at + INTERVAL '2 days' WHERE id = '33000000-0000-0000-0000-000000010002'")
			.update();
		Map<String, Object> changedAssignment = jdbc.sql("SELECT * FROM staff_assignment WHERE id = '09000000-0000-0000-0000-000000010004'")
			.query().singleRow();
		Map<String, Object> changedCredit = jdbc.sql("SELECT * FROM makeup_credit WHERE id = '33000000-0000-0000-0000-000000010002'")
			.query().singleRow();
		Resource script = new ClassPathResource("db/seed/R__seed_10_personal_training.sql");
		new ResourceDatabasePopulator(script).execute(dataSource);
		new ResourceDatabasePopulator(script).execute(dataSource);
		assertThat(jdbc.sql("SELECT * FROM staff_assignment WHERE id = '09000000-0000-0000-0000-000000010004'")
			.query().singleRow()).isEqualTo(changedAssignment);
		assertThat(jdbc.sql("SELECT * FROM makeup_credit WHERE id = '33000000-0000-0000-0000-000000010002'")
			.query().singleRow()).isEqualTo(changedCredit);
		assertThat(rowCounts()).isEqualTo(counts);
	}

	private String seedToken(int accountNo) {
		String uid = jdbc.sql("SELECT external_uid FROM identity_link WHERE account_id = CAST(:id AS UUID) AND provider = 'FIREBASE'")
			.param("id", "03000000-0000-0000-0000-" + "%012d".formatted(accountNo)).query(String.class).single();
		String token = "seed-trainer-" + accountNo;
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return token;
	}

	private String seedGet(String token, String path, boolean calendar) throws Exception {
		var request = get(path).header("Authorization", "Bearer " + token);
		if (calendar) {
			ZoneId zone = ZoneId.of("Europe/Warsaw");
			LocalDate today = LocalDate.now(zone);
			request.param("from", today.minusDays(7).atStartOfDay(zone).toInstant().toString())
				.param("to", today.plusDays(40).atStartOfDay(zone).toInstant().toString());
		}
		return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private Map<String, Object> makeupExample() {
		return jdbc.sql("""
				SELECT * FROM makeup_credit WHERE id = '33000000-0000-0000-0000-000000000002'
				""").query().singleRow();
	}

	@Test
	@Transactional
	void personalActionExamplesKeepACancellationAndNeverReopenTheCreditOnRerun() {
		Map<String, Long> beforeCounts = rowCounts();
		jdbc.sql("UPDATE reservation SET status='CANCELLED', personal_cancellation_effect='MAKEUP_RETURNED', version=1 WHERE id='30000000-0000-0000-0000-000000013005'").update();
		jdbc.sql("UPDATE makeup_credit SET status='AVAILABLE', used_at=NULL, used_reservation_id=NULL WHERE id='33000000-0000-0000-0000-000000013001'").update();
		var reservation = jdbc.sql("SELECT * FROM reservation WHERE id='30000000-0000-0000-0000-000000013005'").query().singleRow();
		var credit = jdbc.sql("SELECT * FROM makeup_credit WHERE id='33000000-0000-0000-0000-000000013001'").query().singleRow();
		var seed = new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_13_personal_training_actions.sql"));
		seed.execute(dataSource); seed.execute(dataSource);
		assertThat(rowCounts()).isEqualTo(beforeCounts);
		assertThat(jdbc.sql("SELECT * FROM reservation WHERE id='30000000-0000-0000-0000-000000013005'").query().singleRow()).isEqualTo(reservation);
		assertThat(jdbc.sql("SELECT * FROM makeup_credit WHERE id='33000000-0000-0000-0000-000000013001'").query().singleRow()).isEqualTo(credit);
	}

	@Test
	void familyScheduleSeedOffersBothCancellationConsequencesAndSeparateChildEvents() throws Exception {
		String token = seedToken(6);
		String parent = "/api/v1/me/profiles/07000000-0000-0000-0000-000000000003";
		String child = "/api/v1/me/profiles/07000000-0000-0000-0000-000000000004";
		String early = seedGet(token, parent + "/personal-trainings/30000000-0000-0000-0000-000000013009", false);
		assertThat(JsonPath.<String>read(early, "$.cancellation.effect")).isEqualTo("MAKEUP_GRANTED");
		assertThat(JsonPath.<Boolean>read(early, "$.cancellation.late")).isFalse();
		String late = seedGet(token, parent + "/personal-trainings/30000000-0000-0000-0000-000000013010", false);
		assertThat(JsonPath.<String>read(late, "$.cancellation.effect")).isEqualTo("ENTRY_FORFEITED");
		assertThat(JsonPath.<Boolean>read(late, "$.cancellation.allowed")).isTrue();
		assertThat(JsonPath.<Boolean>read(late, "$.cancellation.late")).isTrue();
		String personal = seedGet(token, child + "/personal-trainings", true);
		assertThat(JsonPath.<List<String>>read(personal, "$.items[*].id"))
			.contains("19000000-0000-0000-0000-000000013011")
			.doesNotContain("19000000-0000-0000-0000-000000013009");
		String groups = seedGet(token, child + "/reservations", true);
		assertThat(JsonPath.<List<String>>read(groups, "$.items[*].id"))
			.contains("19000000-0000-0000-0000-000000013012");
		var balance = jdbc.sql("""
			SELECT p.entries_remaining::INT AS remaining, SUM(l.entries_delta)::INT AS ledger
			FROM customer_pass p JOIN pass_ledger l ON l.pass_id=p.id
			WHERE p.id='28000000-0000-0000-0000-000000013003' GROUP BY p.id
			""").query().singleRow();
		assertThat(balance.get("remaining")).isEqualTo(balance.get("ledger"));
	}

	@Test
	@Transactional
	void passUsageExamplesBalanceTheirLedgerAndPreserveTesterChanges() {
		assertThat(jdbc.sql("""
				SELECT pass.entries_remaining::INT AS remaining, sum(ledger.entries_delta)::INT AS balance
				FROM customer_pass pass JOIN pass_ledger ledger ON ledger.pass_id = pass.id
				WHERE pass.id BETWEEN '28000000-0000-0000-0000-000000011001' AND '28000000-0000-0000-0000-000000011006'
				  AND pass.entries_total IS NOT NULL GROUP BY pass.id
				""").query().listOfRows()).hasSize(5).allSatisfy(row -> assertThat(row.get("remaining")).isEqualTo(row.get("balance")));
		Map<String, Long> counts = rowCounts();
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 4 WHERE id = '28000000-0000-0000-0000-000000011001'").update();
		jdbc.sql("UPDATE schedule_event SET starts_at = starts_at + INTERVAL '1 hour', ends_at = ends_at + INTERVAL '1 hour' WHERE id = '19000000-0000-0000-0000-000000011002'").update();
		Map<String, Object> changed = jdbc.sql("SELECT * FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000011002'").query().singleRow();
		Resource script = new ClassPathResource("db/seed/R__seed_11_pass_usage_history.sql");
		new ResourceDatabasePopulator(script).execute(dataSource);
		new ResourceDatabasePopulator(script).execute(dataSource);
		assertThat(jdbc.sql("SELECT entries_remaining::INT FROM customer_pass WHERE id = '28000000-0000-0000-0000-000000011001'").query(Integer.class).single()).isEqualTo(4);
		assertThat(jdbc.sql("SELECT * FROM schedule_event WHERE id = '19000000-0000-0000-0000-000000011002'").query().singleRow()).isEqualTo(changed);
		assertThat(rowCounts()).isEqualTo(counts);
	}

	private Map<String, Long> rowCounts() {
		List<String> tables = jdbc.sql("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
				  AND table_name NOT IN ('flyway_schema_history', 'event_publication')
				""")
			.query(String.class)
			.list();
		return tables.stream()
			.collect(Collectors.toMap(Function.identity(),
					table -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single()));
	}

}
