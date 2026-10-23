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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

/**
 * The dev/stage seed ({@code db/seed}) applies on top of all migrations, fills every table
 * and can run again without errors or duplicates.
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

	private Map<String, Long> rowCounts() {
		List<String> tables = jdbc.sql("""
				SELECT table_name
				FROM information_schema.tables
				WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
				  AND table_name <> 'flyway_schema_history'
				""")
			.query(String.class)
			.list();
		return tables.stream()
			.collect(Collectors.toMap(Function.identity(),
					table -> jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single()));
	}

}
