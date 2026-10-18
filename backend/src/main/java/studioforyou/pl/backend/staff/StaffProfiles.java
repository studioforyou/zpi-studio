package studioforyou.pl.backend.staff;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read access to staff profiles for other modules.
 */
@Repository
public class StaffProfiles {

	private final JdbcClient jdbc;

	public StaffProfiles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** First and last name of the account's staff profile, if it has one. */
	public Optional<String> findDisplayName(UUID accountId) {
		return jdbc.sql("""
				SELECT first_name || ' ' || last_name
				FROM staff_profile
				WHERE account_id = :accountId
				""")
			.param("accountId", accountId)
			.query(String.class)
			.optional();
	}

}
