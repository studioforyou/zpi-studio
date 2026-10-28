package studioforyou.pl.backend.membership;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Participants holding passes, for other modules' reports.
 */
@Repository
public class PassHolders {

	private final JdbcClient jdbc;

	public PassHolders(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Participants with a pass valid on at least one day of {@code [from, to]} (studio dates). Passes
	 * waiting for the payment and cancelled ones are left out; expired and suspended ones count for the
	 * days they were valid.
	 */
	public Set<UUID> findValidBetween(LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT DISTINCT customer_id
				FROM customer_pass
				WHERE status IN ('SCHEDULED', 'ACTIVE', 'SUSPENDED', 'EXPIRED')
				  AND valid_from <= :to AND (valid_until IS NULL OR valid_until >= :from)
				""")
			.param("from", from)
			.param("to", to)
			.query(UUID.class)
			.set();
	}

}
