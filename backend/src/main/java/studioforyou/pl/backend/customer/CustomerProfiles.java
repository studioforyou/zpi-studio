package studioforyou.pl.backend.customer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

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

	/** Names for a caller-authorized participant set; no contacts or participant details. */
	public Map<UUID, String> displayNames(Collection<UUID> profileIds) {
		if (profileIds.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("SELECT id, first_name, last_name FROM customer_profile WHERE id IN (:ids)")
			.param("ids", profileIds).query(ProfileSummary.class).list().stream()
			.collect(Collectors.toMap(ProfileSummary::id, ProfileSummary::displayName));
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

	/**
	 * Changes the name and phone number of the account's own profile, whatever its status. A blank
	 * phone clears it.
	 *
	 * @return whether the account has an own profile
	 */
	public boolean updateOwnProfile(UUID accountId, String firstName, String lastName, String phone) {
		return jdbc.sql("""
				UPDATE customer_profile
				SET first_name = :firstName, last_name = :lastName, phone = :phone, updated_at = now()
				WHERE account_id = :accountId
				""")
			.param("accountId", accountId)
			.param("firstName", firstName.strip())
			.param("lastName", lastName.strip())
			.param("phone", (phone == null || phone.isBlank()) ? null : phone.strip())
			.update() > 0;
	}

	/** Changes only the name of the account's own profile, whatever its status. */
	public void renameOwnProfile(UUID accountId, String firstName, String lastName) {
		jdbc.sql("""
				UPDATE customer_profile
				SET first_name = :firstName, last_name = :lastName, updated_at = now()
				WHERE account_id = :accountId
				""")
			.param("accountId", accountId)
			.param("firstName", firstName.strip())
			.param("lastName", lastName.strip())
			.update();
	}

	/**
	 * Active children the account's own profile is a current guardian of, sorted by first name, with
	 * the details an administrator may change.
	 */
	public List<ChildDetails> findChildrenOfAccount(UUID accountId) {
		return jdbc.sql("""
				SELECT c.id, c.first_name, c.last_name, c.birth_date, c.emergency_contact_name, c.emergency_phone
				FROM customer_profile guardian
				JOIN guardianship g ON g.guardian_id = guardian.id AND g.ended_at IS NULL
				JOIN customer_profile c ON c.id = g.child_id AND c.status = 'ACTIVE'
				WHERE guardian.account_id = :accountId
				ORDER BY c.first_name, c.last_name, c.id
				""")
			.param("accountId", accountId)
			.query(ChildDetails.class)
			.list();
	}

	/**
	 * Replaces the details of an active child the account's own profile is a current guardian of.
	 * The caller validates and trims the details.
	 *
	 * @return {@code false} if there is no such child
	 */
	public boolean updateChildOfAccount(UUID accountId, ChildDetails child) {
		return jdbc.sql("""
				UPDATE customer_profile c
				SET first_name = :firstName, last_name = :lastName, birth_date = :birthDate,
				    emergency_contact_name = :emergencyContactName, emergency_phone = :emergencyPhone,
				    updated_at = now()
				FROM guardianship g
				JOIN customer_profile guardian ON guardian.id = g.guardian_id
				WHERE c.id = :childId AND c.status = 'ACTIVE' AND g.child_id = c.id AND g.ended_at IS NULL
				  AND guardian.account_id = :accountId
				""")
			.param("accountId", accountId)
			.param("childId", child.id())
			.param("firstName", child.firstName())
			.param("lastName", child.lastName())
			.param("birthDate", child.birthDate())
			.param("emergencyContactName", child.emergencyContactName())
			.param("emergencyPhone", child.emergencyPhone())
			.update() > 0;
	}

	/**
	 * Archives the account's own profile when the account is closed and clears its contact data.
	 * The name stays for the history of passes and bookings; children keep their own profiles.
	 */
	public void archiveOwnProfile(UUID accountId) {
		jdbc.sql("""
				UPDATE customer_profile
				SET status = 'ARCHIVED', phone = NULL, emergency_contact_name = NULL, emergency_phone = NULL,
				    updated_at = now()
				WHERE account_id = :accountId
				""")
			.param("accountId", accountId)
			.update();
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
	 * A caller-authorized set of participant profiles, whatever their status; unknown ids are left
	 * out. No contacts.
	 */
	public List<ProfileDetails> findAllById(Collection<UUID> profileIds) {
		if (profileIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id, kind, first_name, last_name, birth_date, status = 'ACTIVE' AS active
				FROM customer_profile
				WHERE id IN (:ids)
				""")
			.param("ids", profileIds)
			.query(ProfileDetails.class)
			.list();
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

	/**
	 * Participant profiles without an account of their own (children, adults added by the studio)
	 * created in {@code [from, to)}, whatever their status now.
	 */
	public long countCreatedWithoutAccount(Instant from, Instant to) {
		return jdbc.sql("""
				SELECT count(*)
				FROM customer_profile
				WHERE account_id IS NULL AND created_at >= :from AND created_at < :to
				""")
			.param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
			.param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
			.query(Long.class)
			.single();
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

	/** A child's personal details; {@code null} for an unknown birth date or missing emergency contact. */
	public record ChildDetails(UUID id, String firstName, String lastName, @Nullable LocalDate birthDate,
			@Nullable String emergencyContactName, @Nullable String emergencyPhone) {

	}

	public record ProfileSummary(UUID id, String firstName, String lastName) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

}
