package studioforyou.pl.backend.customer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.shared.api.PageResponse;


/**
 * Access to customer profiles for other modules.
 */
@Repository
public class CustomerProfiles {

	private final JdbcClient jdbc;

	private final AccountEmails accountEmails;

	public CustomerProfiles(JdbcClient jdbc, AccountEmails accountEmails) {
		this.jdbc = jdbc;
		this.accountEmails = accountEmails;
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
	 * @param byEmail whether the query also matches part of the e-mail address (a child matches
	 * its guardian's address); only for callers allowed to see participants' addresses
	 */
	public PageResponse<ProfileDetails> searchActive(String query, boolean byEmail, int page, int size) {
		Filter filter = activeMatching(query, byEmail);
		var count = filter.bind(jdbc.sql("SELECT count(*) FROM customer_profile WHERE " + filter.where()));
		var select = filter.bind(jdbc.sql("""
				SELECT id, kind, first_name, last_name, birth_date, TRUE AS active
				FROM customer_profile
				WHERE %s
				ORDER BY last_name, first_name, id
				LIMIT :limit OFFSET :offset
				""".formatted(filter.where())))
			.param("limit", size)
			.param("offset", (long) page * size);
		long total = count.query(Long.class).single();
		return PageResponse.of(select.query(ProfileDetails.class).list(), page, size, total);
	}

	/**
	 * IDs of the active participants {@link #searchActive} finds, matching e-mail addresses too,
	 * in the same order, at most {@code limit} of them.
	 */
	public List<UUID> findActiveIds(String query, int limit) {
		Filter filter = activeMatching(query, true);
		return filter.bind(jdbc.sql("""
				SELECT id FROM customer_profile
				WHERE %s
				ORDER BY last_name, first_name, id
				LIMIT :limit
				""".formatted(filter.where())))
			.param("limit", limit)
			.query(UUID.class)
			.list();
	}

	/**
	 * Names and contact e-mail addresses of the given participants, for the studio staff. A child
	 * has no account, so its contact is the account of its earliest current guardian.
	 */
	public Map<UUID, ParticipantContact> contacts(Collection<UUID> profileIds) {
		if (profileIds.isEmpty()) {
			return Map.of();
		}
		record Row(UUID id, CustomerKind kind, String firstName, String lastName, UUID accountId,
				boolean viaGuardian) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT p.id, p.kind, p.first_name, p.last_name,
				       COALESCE(p.account_id, (
				           SELECT guardian.account_id
				           FROM guardianship g
				           JOIN customer_profile guardian ON guardian.id = g.guardian_id
				           WHERE g.child_id = p.id AND g.ended_at IS NULL AND guardian.account_id IS NOT NULL
				           ORDER BY g.created_at, guardian.id
				           LIMIT 1)) AS account_id,
				       p.account_id IS NULL AS via_guardian
				FROM customer_profile p
				WHERE p.id IN (:ids)
				""")
			.param("ids", profileIds)
			.query(Row.class)
			.list();
		Map<UUID, String> emails = accountEmails.emailsOf(
				rows.stream().map(Row::accountId).filter(Objects::nonNull).collect(Collectors.toSet()));
		return rows.stream()
			.collect(Collectors.toMap(Row::id,
					(row) -> new ParticipantContact(row.id(), row.kind(), row.firstName(), row.lastName(),
							(row.accountId() != null) ? emails.get(row.accountId()) : null,
							row.accountId() != null && row.viaGuardian())));
	}

	private Filter activeMatching(String query, boolean byEmail) {
		if (query == null || query.isBlank()) {
			return new Filter("status = 'ACTIVE'", null, Set.of());
		}
		String pattern = "%" + escapeLike(query.strip()) + "%";
		Set<UUID> emailAccounts = byEmail ? accountEmails.findByEmailContaining(query) : Set.of();
		String where = """
				status = 'ACTIVE'
				  AND (first_name || ' ' || last_name ILIKE :pattern
				    OR last_name || ' ' || first_name ILIKE :pattern
				    OR phone ILIKE :pattern""";
		if (!emailAccounts.isEmpty()) {
			where += """

					    OR account_id IN (:emailAccounts)
					    OR id IN (SELECT g.child_id FROM guardianship g
					              JOIN customer_profile guardian ON guardian.id = g.guardian_id
					              WHERE g.ended_at IS NULL AND guardian.account_id IN (:emailAccounts))""";
		}
		return new Filter(where + ")", pattern, emailAccounts);
	}

	/** A search condition over {@code customer_profile} and the values it binds. */
	private record Filter(String where, String pattern, Set<UUID> emailAccounts) {

		JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
			if (pattern != null) {
				spec = spec.param("pattern", pattern);
			}
			if (!emailAccounts.isEmpty()) {
				spec = spec.param("emailAccounts", emailAccounts);
			}
			return spec;
		}

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

	/**
	 * A participant with the e-mail address the studio can tell them apart by.
	 *
	 * @param email the address of the participant's account or, for a child, of its guardian's
	 * account; {@code null} when there is none
	 * @param emailOfGuardian whether {@code email} belongs to the guardian
	 */
	public record ParticipantContact(UUID id, CustomerKind kind, String firstName, String lastName, String email,
			boolean emailOfGuardian) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

	public record ProfileSummary(UUID id, String firstName, String lastName) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

}
