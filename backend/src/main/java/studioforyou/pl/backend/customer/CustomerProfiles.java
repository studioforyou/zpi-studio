package studioforyou.pl.backend.customer;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.shared.api.PageResponse;


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

	/** Any participant profile, whatever its status. */
	public Optional<ProfileDetails> findById(UUID profileId) {
		return jdbc.sql("""
				SELECT id, kind, first_name, last_name, birth_date, status = 'ACTIVE' AS active
				FROM customer_profile
				WHERE id = :id
				""")
			.param("id", profileId)
			.query(ProfileDetails.class)
			.optional();
	}

	/**
	 * Active participants, adults and children, sorted by last and first name.
	 *
	 * @param query part of the first and/or last name, in any order, or of the phone number;
	 * all active participants when blank
	 */
	public PageResponse<ProfileDetails> searchActive(String query, int page, int size) {
		String where = "status = 'ACTIVE'";
		String pattern = null;
		if (query != null && !query.isBlank()) {
			where += """
					 AND (first_name || ' ' || last_name ILIKE :pattern
					   OR last_name || ' ' || first_name ILIKE :pattern
					   OR phone ILIKE :pattern)""";
			pattern = "%" + escapeLike(query.strip()) + "%";
		}
		var count = jdbc.sql("SELECT count(*) FROM customer_profile WHERE " + where);
		var select = jdbc.sql("""
				SELECT id, kind, first_name, last_name, birth_date, TRUE AS active
				FROM customer_profile
				WHERE %s
				ORDER BY last_name, first_name, id
				LIMIT :limit OFFSET :offset
				""".formatted(where))
			.param("limit", size)
			.param("offset", (long) page * size);
		if (pattern != null) {
			count = count.param("pattern", pattern);
			select = select.param("pattern", pattern);
		}
		long total = count.query(Long.class).single();
		return PageResponse.of(select.query(ProfileDetails.class).list(), page, size, total);
	}

	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/**
	 * @param birthDate the date of birth, or {@code null} if unknown
	 * @param active whether the profile has status {@code ACTIVE}
	 */
	public record ProfileDetails(UUID id, CustomerKind kind, String firstName, String lastName, LocalDate birthDate,
			boolean active) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

	/**
	 * Whether the account may act for the profile: it is the account's own active profile,
	 * or an active child the account's own profile is a current guardian of.
	 */
	public boolean isActingFor(UUID accountId, UUID profileId) {
		return jdbc.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM customer_profile
				  WHERE id = :profileId AND account_id = :accountId AND status = 'ACTIVE'
				  UNION ALL
				  SELECT 1
				  FROM guardianship g
				  JOIN customer_profile guardian ON guardian.id = g.guardian_id
				  JOIN customer_profile c ON c.id = g.child_id
				  WHERE g.child_id = :profileId AND g.ended_at IS NULL AND c.status = 'ACTIVE'
				    AND guardian.account_id = :accountId AND guardian.status = 'ACTIVE'
				)
				""")
			.param("accountId", accountId)
			.param("profileId", profileId)
			.query(Boolean.class)
			.single();
	}

	public record ProfileSummary(UUID id, String firstName, String lastName) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

}
