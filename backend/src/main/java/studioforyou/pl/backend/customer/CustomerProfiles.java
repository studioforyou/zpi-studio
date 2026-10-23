package studioforyou.pl.backend.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Access to customer profiles for other modules.
 */
@Repository
public class CustomerProfiles {

	private final JdbcClient jdbc;

	public CustomerProfiles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The account's own customer profile (an adult client), if it has one. */
	public Optional<ProfileSummary> findOwnProfile(UUID accountId) {
		return jdbc.sql("""
				SELECT id, first_name, last_name
				FROM customer_profile
				WHERE account_id = :accountId AND status = 'ACTIVE'
				""")
			.param("accountId", accountId)
			.query(ProfileSummary.class)
			.optional();
	}

	/** Creates the own (adult) customer profile of a new account. */
	public UUID createOwnProfile(UUID accountId, String firstName, String lastName, UUID createdBy) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, first_name, last_name, created_by)
				VALUES (:accountId, :firstName, :lastName, :createdBy)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("createdBy", createdBy)
			.query(UUID.class)
			.single();
	}

	/** Active child profiles the given profile is a current guardian of. */
	public List<ProfileSummary> findChildrenOf(UUID guardianProfileId) {
		return jdbc.sql("""
				SELECT c.id, c.first_name, c.last_name
				FROM guardianship g
				JOIN customer_profile c ON c.id = g.child_id
				WHERE g.guardian_id = :guardianId AND g.ended_at IS NULL AND c.status = 'ACTIVE'
				ORDER BY c.first_name, c.last_name
				""")
			.param("guardianId", guardianProfileId)
			.query(ProfileSummary.class)
			.list();
	}

	public record ProfileSummary(UUID id, String firstName, String lastName) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

}
