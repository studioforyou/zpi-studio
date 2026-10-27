package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PassSuspensionQueries {

	private final JdbcClient jdbc;

	PassSuspensionQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	boolean suspendedOn(UUID passId, LocalDate day) {
		return jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM pass_suspension
				    WHERE pass_id = :passId AND cancelled_at IS NULL
				      AND :day BETWEEN starts_on AND ends_on
				)
				""")
			.param("passId", passId)
			.param("day", day)
			.query(Boolean.class)
			.single();
	}

}
