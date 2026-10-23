package studioforyou.pl.backend.staff;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Access to staff profiles for other modules.
 */
@Repository
public class StaffProfiles {

	private final JdbcClient jdbc;

	public StaffProfiles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The account's staff profile, if it has one. */
	public Optional<StaffSummary> findByAccount(UUID accountId) {
		return jdbc.sql("""
				SELECT id, first_name || ' ' || last_name AS display_name
				FROM staff_profile
				WHERE account_id = :accountId
				""")
			.param("accountId", accountId)
			.query(StaffSummary.class)
			.optional();
	}

	/** Creates the staff profile of a new account. */
	public UUID create(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name)
				VALUES (:accountId, :firstName, :lastName)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.query(UUID.class)
			.single();
	}

	public record StaffSummary(UUID id, String displayName) {

	}

}
