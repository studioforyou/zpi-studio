package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

	@Autowired
	private Flyway flyway;

	@Test
	void contextLoads() {
	}

	@Test
	void allMigrationsAreApplied() {
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(flyway.info().current()).isNotNull();
	}

}
