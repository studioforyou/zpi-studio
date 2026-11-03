package studioforyou.pl.backend.identity.accounts;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ChildDetails;
import studioforyou.pl.backend.identity.AccountLookup;
import studioforyou.pl.backend.identity.AccountStatus;
import studioforyou.pl.backend.identity.firebase.FirebaseUsers;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;
import studioforyou.pl.backend.shared.api.UserText;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.ProfileChanges;

/**
 * Administrators look up, edit, suspend and close accounts of clients and staff. Accounts are
 * created by invitation or, for administrators, directly; closing replaces deleting, because
 * passes, bookings and payments keep referring to the account.
 */
@Service
class AccountManagementService {

	private static final Logger logger = LoggerFactory.getLogger(AccountManagementService.class);

	/**
	 * One row per account: names and phone from the own customer profile or else the staff profile
	 * (each account has at most one of each).
	 */
	private static final String ACCOUNTS = """
			SELECT a.id, a.email, a.status, a.created_at, a.activated_at,
			       COALESCE(c.first_name, s.first_name) AS first_name,
			       COALESCE(c.last_name, s.last_name) AS last_name,
			       COALESCE(c.phone, s.phone) AS phone,
			       c.id AS customer_profile_id, s.id AS staff_profile_id,
			       ARRAY(SELECT r.code FROM account_role ar JOIN role r ON r.id = ar.role_id
			             WHERE ar.account_id = a.id ORDER BY r.code) AS roles
			FROM account a
			LEFT JOIN customer_profile c ON c.account_id = a.id
			LEFT JOIN staff_profile s ON s.account_id = a.id
			""";

	/**
	 * One row per person: the owner of each account (its own customer profile and staff profile are
	 * the same person) and each active child the account's own profile is a current guardian of.
	 */
	private static final String PEOPLE = """
			SELECT a.id, COALESCE(c.id, s.id) AS profile_id, a.email, a.status, a.created_at,
			       COALESCE(c.first_name, s.first_name) AS first_name,
			       COALESCE(c.last_name, s.last_name) AS last_name,
			       COALESCE(c.phone, s.phone) AS phone,
			       ARRAY(SELECT r.code::text FROM account_role ar JOIN role r ON r.id = ar.role_id
			             WHERE ar.account_id = a.id ORDER BY r.code) AS roles
			FROM account a
			LEFT JOIN customer_profile c ON c.account_id = a.id
			LEFT JOIN staff_profile s ON s.account_id = a.id
			UNION ALL
			SELECT a.id, child.id, a.email, a.status, a.created_at, child.first_name, child.last_name, NULL,
			       ARRAY['CHILD']
			FROM account a
			JOIN customer_profile guardian ON guardian.account_id = a.id
			JOIN guardianship g ON g.guardian_id = guardian.id AND g.ended_at IS NULL
			JOIN customer_profile child ON child.id = g.child_id AND child.status = 'ACTIVE'
			""";

	private final JdbcClient jdbc;

	private final FirebaseUsers firebaseUsers;

	private final CustomerProfiles customerProfiles;

	private final StaffProfiles staffProfiles;

	AccountManagementService(JdbcClient jdbc, FirebaseUsers firebaseUsers, CustomerProfiles customerProfiles,
			StaffProfiles staffProfiles) {
		this.jdbc = jdbc;
		this.firebaseUsers = firebaseUsers;
		this.customerProfiles = customerProfiles;
		this.staffProfiles = staffProfiles;
	}

	/**
	 * People with accounts and the children of those accounts, sorted by last and first name; accounts
	 * without a profile come last.
	 *
	 * @param query part of the name (in any order), the account's e-mail or the phone; everyone when blank
	 * @param role only people with this role code, {@code CHILD} for children; everyone when {@code null}
	 * @param status only accounts with this status; all but {@code CLOSED} when {@code null}
	 */
	@Transactional(readOnly = true)
	public PageResponse<AccountSummary> search(@Nullable String query, @Nullable String role,
			@Nullable AccountStatus status, int page, int size) {
		StringBuilder where = new StringBuilder(
				(status == null) ? "WHERE status <> 'CLOSED'" : "WHERE status = :status");
		String pattern = null;
		if (query != null && !query.isBlank()) {
			where.append("""

					  AND (first_name || ' ' || last_name ILIKE :pattern
					   OR last_name || ' ' || first_name ILIKE :pattern
					   OR email ILIKE :pattern
					   OR phone ILIKE :pattern)""");
			pattern = "%" + escapeLike(query.strip()) + "%";
		}
		if (role != null) {
			where.append(" AND :role = ANY (roles)");
		}
		String accounts = "WITH accounts AS (" + PEOPLE + ") ";
		var count = jdbc.sql(accounts + "SELECT count(*) FROM accounts " + where);
		var select = jdbc.sql(accounts + """
				SELECT * FROM accounts %s
				ORDER BY last_name NULLS LAST, first_name, lower(email), id, profile_id
				LIMIT :limit OFFSET :offset
				""".formatted(where))
			.param("limit", size)
			.param("offset", (long) page * size);
		if (status != null) {
			count = count.param("status", status.name());
			select = select.param("status", status.name());
		}
		if (pattern != null) {
			count = count.param("pattern", pattern);
			select = select.param("pattern", pattern);
		}
		if (role != null) {
			count = count.param("role", role);
			select = select.param("role", role);
		}
		long total = count.query(Long.class).single();
		List<AccountSummary> items = select.query((rs, rowNum) -> new AccountSummary(rs.getObject("id", UUID.class),
				rs.getObject("profile_id", UUID.class), rs.getString("email"), AccountStatus.valueOf(rs.getString("status")), roles(rs), displayName(rs),
				rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
				rs.getObject("created_at", OffsetDateTime.class)))
			.list();
		return PageResponse.of(items, page, size, total);
	}

	/**
	 * @throws ApiException {@code NOT_FOUND} if there is no such account
	 */
	@Transactional(readOnly = true)
	public AccountDetails get(UUID accountId) {
		List<AccountProfile> profiles = profiles(accountId);
		return jdbc.sql("WITH accounts AS (" + ACCOUNTS + """
				)
				SELECT accounts.*,
				       (SELECT max(i.expires_at) FROM account_invitation i
				        WHERE i.account_id = accounts.id AND i.used_at IS NULL AND i.revoked_at IS NULL)
				         AS invitation_expires_at,
				       EXISTS (SELECT 1 FROM identity_link l WHERE l.account_id = accounts.id) AS has_login
				FROM accounts
				WHERE id = :id
				""")
			.param("id", accountId)
			.query((rs, rowNum) -> new AccountDetails(rs.getObject("id", UUID.class), rs.getString("email"),
					AccountStatus.valueOf(rs.getString("status")), roles(rs), displayName(rs),
					rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
					rs.getObject("created_at", OffsetDateTime.class),
					rs.getObject("activated_at", OffsetDateTime.class),
					rs.getObject("customer_profile_id", UUID.class), rs.getObject("staff_profile_id", UUID.class),
					rs.getObject("invitation_expires_at", OffsetDateTime.class), rs.getBoolean("has_login"),
					profiles))
			.optional()
			.orElseThrow(AccountManagementService::notFound);
	}

	/**
	 * Replaces the e-mail, names and phone. Names are saved in the own client profile and the staff
	 * profile; the phone in the client profile, or the staff profile when there is no client profile.
	 * A new e-mail is also set on the linked Firebase user, and set back if the transaction rolls back.
	 *
	 * @throws ApiException {@code NOT_FOUND}; {@code ACCOUNT_STATUS_CONFLICT} for a closed account;
	 * {@code ACCOUNT_ALREADY_EXISTS} if another account or Firebase user has the e-mail
	 */
	@Transactional
	public AccountDetails update(UUID accountId, UpdateAccountRequest request) {
		LockedAccount account = lock(accountId);
		if (account.status() == AccountStatus.CLOSED) {
			throw new ApiException(ErrorCode.ACCOUNT_STATUS_CONFLICT, "A closed account cannot be changed.");
		}

		String email = request.email().strip();
		if (!email.equals(account.email())) {
			changeEmail(account, email);
		}
		boolean hasProfile = customerProfiles.updateOwnProfile(accountId, request.firstName(), request.lastName(),
				request.phone());
		if (staffProfiles.findByAccount(accountId).isPresent()) {
			staffProfiles.updateByAccount(accountId, new ProfileChanges(true, request.firstName(), true,
					request.lastName(), !hasProfile, request.phone()));
			hasProfile = true;
		}
		if (!hasProfile) {
			logger.warn("Account {} has no profile; only its e-mail was updated", accountId);
		}
		return get(accountId);
	}

	/**
	 * Replaces the details of the account's staff profile. The names are also saved in the own client
	 * profile, so both profiles keep the same name; phone and description stay per profile.
	 *
	 * @throws ApiException {@code NOT_FOUND} without the account or its staff profile;
	 * {@code ACCOUNT_STATUS_CONFLICT} for a closed account
	 */
	@Transactional
	public AccountDetails updateStaffProfile(UUID accountId, UpdateStaffDetailsRequest request) {
		requireOpen(lock(accountId));
		if (staffProfiles.findByAccount(accountId).isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Staff profile not found.");
		}
		staffProfiles.updateByAccount(accountId, new ProfileChanges(true, request.firstName(), true,
				request.lastName(), true, request.phone()));
		staffProfiles.updateBioByAccount(accountId, request.bio());
		customerProfiles.renameOwnProfile(accountId, UserText.trim(request.firstName()),
				UserText.trim(request.lastName()));
		return get(accountId);
	}

	/**
	 * Replaces the details of a child the account's own profile is a current guardian of.
	 *
	 * @throws ApiException {@code NOT_FOUND} without the account or such a child;
	 * {@code ACCOUNT_STATUS_CONFLICT} for a closed account
	 */
	@Transactional
	public AccountDetails updateChild(UUID accountId, UUID childId, UpdateChildRequest request) {
		requireOpen(lock(accountId));
		boolean updated = customerProfiles.updateChildOfAccount(accountId,
				new ChildDetails(childId, UserText.trim(request.firstName()), UserText.trim(request.lastName()),
						request.birthDate(), UserText.trimToNull(request.emergencyContactName()),
						UserText.trimToNull(request.emergencyPhone())));
		if (!updated) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Child profile not found.");
		}
		return get(accountId);
	}

	/**
	 * Suspends an active account: it keeps its data but loses access until it is reactivated.
	 *
	 * @param changedBy the administrator; may not suspend their own account
	 * @throws ApiException {@code NOT_FOUND}, {@code OWN_ACCOUNT_CHANGE}, or {@code ACCOUNT_STATUS_CONFLICT}
	 * if the account is not active
	 */
	@Transactional
	public AccountDetails suspend(UUID accountId, UUID changedBy, @Nullable String reason) {
		changeStatus(accountId, changedBy, AccountStatus.ACTIVE, AccountStatus.SUSPENDED,
				reasonOr(reason, "Suspended by an administrator"));
		return get(accountId);
	}

	/**
	 * Gives a suspended account its access back.
	 *
	 * @throws ApiException {@code NOT_FOUND}, {@code OWN_ACCOUNT_CHANGE}, or {@code ACCOUNT_STATUS_CONFLICT}
	 * if the account is not suspended
	 */
	@Transactional
	public AccountDetails reactivate(UUID accountId, UUID changedBy, @Nullable String reason) {
		changeStatus(accountId, changedBy, AccountStatus.SUSPENDED, AccountStatus.ACTIVE,
				reasonOr(reason, "Reactivated by an administrator"));
		return get(accountId);
	}

	/**
	 * Closes the account for good, in place of deleting it: withdraws pending invitations, unlinks and
	 * (after the commit) deletes the Firebase user, archives the profiles with their contact data and
	 * replaces the e-mail, so it can be invited again. Names stay for the history of passes and bookings.
	 *
	 * @throws ApiException {@code NOT_FOUND}, {@code OWN_ACCOUNT_CHANGE}, or {@code ACCOUNT_STATUS_CONFLICT}
	 * if the account is already closed
	 */
	@Transactional
	public void close(UUID accountId, UUID closedBy, @Nullable String reason) {
		LockedAccount account = lock(accountId);
		if (accountId.equals(closedBy)) {
			throw ownAccount();
		}
		if (account.status() == AccountStatus.CLOSED) {
			throw new ApiException(ErrorCode.ACCOUNT_STATUS_CONFLICT, "This account is already closed.");
		}

		List<String> uids = jdbc.sql("DELETE FROM identity_link WHERE account_id = :id RETURNING external_uid")
			.param("id", accountId)
			.query(String.class)
			.list();
		jdbc.sql("""
				UPDATE account_invitation SET revoked_at = now()
				WHERE account_id = :id AND used_at IS NULL AND revoked_at IS NULL
				""")
			.param("id", accountId)
			.update();
		customerProfiles.archiveOwnProfile(accountId);
		staffProfiles.deactivateByAccount(accountId);
		jdbc.sql("""
				UPDATE account SET status = 'CLOSED', email = :email, updated_at = now()
				WHERE id = :id
				""")
			.param("email", closedEmail(accountId))
			.param("id", accountId)
			.update();
		recordStatusChange(accountId, account.status(), AccountStatus.CLOSED, closedBy,
				reasonOr(reason, "Closed by an administrator"));
		uids.forEach(this::deleteUserAfterCommit);
	}

	private void changeStatus(UUID accountId, UUID changedBy, AccountStatus from, AccountStatus to, String reason) {
		LockedAccount account = lock(accountId);
		if (accountId.equals(changedBy)) {
			throw ownAccount();
		}
		if (account.status() != from) {
			throw new ApiException(ErrorCode.ACCOUNT_STATUS_CONFLICT,
					"Only an account with status " + from + " can get status " + to + ".");
		}
		jdbc.sql("UPDATE account SET status = :status, updated_at = now() WHERE id = :id")
			.param("status", to.name())
			.param("id", accountId)
			.update();
		recordStatusChange(accountId, from, to, changedBy, reason);
	}

	private void changeEmail(LockedAccount account, String email) {
		try {
			jdbc.sql("UPDATE account SET email = :email, updated_at = now() WHERE id = :id")
				.param("email", email)
				.param("id", account.id())
				.update();
		}
		catch (DuplicateKeyException ex) {
			throw accountAlreadyExists();
		}
		Optional<String> uid = jdbc.sql("""
				SELECT external_uid FROM identity_link WHERE account_id = :id AND provider = :provider
				""")
			.param("id", account.id())
			.param("provider", AccountLookup.FIREBASE_PROVIDER)
			.query(String.class)
			.optional();
		if (uid.isPresent()) {
			if (!firebaseUsers.updateEmail(uid.get(), email)) {
				throw accountAlreadyExists();
			}
			restoreEmailOnRollback(uid.get(), account.email());
		}
	}

	private LockedAccount lock(UUID accountId) {
		return jdbc.sql("SELECT id, email, status FROM account WHERE id = :id FOR UPDATE")
			.param("id", accountId)
			.query(LockedAccount.class)
			.optional()
			.orElseThrow(AccountManagementService::notFound);
	}

	private static void requireOpen(LockedAccount account) {
		if (account.status() == AccountStatus.CLOSED) {
			throw new ApiException(ErrorCode.ACCOUNT_STATUS_CONFLICT, "A closed account cannot be changed.");
		}
	}

	/** The own client profile, the staff profile and the active children, each if there is one. */
	private List<AccountProfile> profiles(UUID accountId) {
		List<AccountProfile> profiles = new ArrayList<>();
		jdbc.sql("SELECT id, first_name, last_name, phone FROM customer_profile WHERE account_id = :id")
			.param("id", accountId)
			.query((rs, rowNum) -> new AccountProfile(rs.getObject("id", UUID.class), AccountProfile.Kind.CLIENT,
					rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"), null, null, null,
					null))
			.optional()
			.ifPresent(profiles::add);
		jdbc.sql("SELECT id, first_name, last_name, phone, bio FROM staff_profile WHERE account_id = :id")
			.param("id", accountId)
			.query((rs, rowNum) -> new AccountProfile(rs.getObject("id", UUID.class), AccountProfile.Kind.STAFF,
					rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"),
					rs.getString("bio"), null, null, null))
			.optional()
			.ifPresent(profiles::add);
		for (ChildDetails child : customerProfiles.findChildrenOfAccount(accountId)) {
			profiles.add(new AccountProfile(child.id(), AccountProfile.Kind.CHILD, child.firstName(),
					child.lastName(), null, null, child.birthDate(), child.emergencyContactName(),
					child.emergencyPhone()));
		}
		return profiles;
	}

	private void recordStatusChange(UUID accountId, AccountStatus from, AccountStatus to, UUID changedBy,
			String reason) {
		jdbc.sql("""
				INSERT INTO account_status_change (account_id, from_status, to_status, changed_by, reason)
				VALUES (:accountId, :from, :to, :changedBy, :reason)
				""")
			.param("accountId", accountId)
			.param("from", from.name())
			.param("to", to.name())
			.param("changedBy", changedBy)
			.param("reason", reason)
			.update();
	}

	private void deleteUserAfterCommit(String uid) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				// The link is already gone, so a leftover Firebase user cannot reach the account.
				try {
					firebaseUsers.delete(uid);
				}
				catch (RuntimeException ex) {
					logger.warn("Could not delete the Firebase user {} of a closed account", uid, ex);
				}
			}
		});
	}

	private void restoreEmailOnRollback(String uid, String previousEmail) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					firebaseUsers.updateEmail(uid, previousEmail);
				}
			}
		});
	}

	/** A closed account gives its e-mail up; the replacement is unique and can never receive mail. */
	static String closedEmail(UUID accountId) {
		return "closed-" + accountId + "@closed.invalid";
	}

	private static List<String> roles(ResultSet rs) throws SQLException {
		Array roles = rs.getArray("roles");
		return Arrays.asList((String[]) roles.getArray());
	}

	private static String displayName(ResultSet rs) throws SQLException {
		String firstName = rs.getString("first_name");
		return (firstName == null) ? rs.getString("email") : firstName + " " + rs.getString("last_name");
	}

	private static String reasonOr(@Nullable String reason, String fallback) {
		return (reason == null || reason.isBlank()) ? fallback : reason.strip();
	}

	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Account not found.");
	}

	private static ApiException ownAccount() {
		return new ApiException(ErrorCode.OWN_ACCOUNT_CHANGE,
				"Administrators cannot suspend or close their own account.");
	}

	private static ApiException accountAlreadyExists() {
		return new ApiException(ErrorCode.ACCOUNT_ALREADY_EXISTS, "An account with this e-mail already exists.");
	}

	record LockedAccount(UUID id, String email, AccountStatus status) {
	}

}
