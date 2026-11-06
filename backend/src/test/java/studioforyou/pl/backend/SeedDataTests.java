package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dev/stage seed ({@code db/seed}) applies on top of all migrations, fills every table
 * and can run again without errors or duplicates. The event publication registry is runtime
 * state of Spring Modulith (rows exist only while a listener has not finished), so it is left out.
 */
@SpringBootTest
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class SeedDataTests {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

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

	private Map<String, Object> makeupExample() {
		return jdbc.sql("""
				SELECT * FROM makeup_credit WHERE id = '33000000-0000-0000-0000-000000000002'
				""").query().singleRow();
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
