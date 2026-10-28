package studioforyou.pl.backend.identity.admin;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import studioforyou.pl.backend.identity.AccountLookup;
import studioforyou.pl.backend.identity.firebase.FirebaseUsers;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * Administrators create other administrators' accounts directly, without an invitation e-mail. The
 * backend generates the password and returns it once; the account is {@code ACTIVE} and can sign in
 * to the panel right away.
 */
@Service
class AdminAccountService {

	private final JdbcClient jdbc;

	private final FirebaseUsers firebaseUsers;

	private final StaffProfiles staffProfiles;

	AdminAccountService(JdbcClient jdbc, FirebaseUsers firebaseUsers, StaffProfiles staffProfiles) {
		this.jdbc = jdbc;
		this.firebaseUsers = firebaseUsers;
		this.staffProfiles = staffProfiles;
	}

	/**
	 * Creates an {@code ACTIVE} account with the {@code ADMIN} role and a staff profile, and a Firebase
	 * user with a generated password linked to it. If the transaction rolls back, the Firebase user is
	 * deleted again.
	 *
	 * @param createdBy the account of the administrator creating the account
	 * @throws ApiException {@code ACCOUNT_ALREADY_EXISTS} if an account or a Firebase user with this
	 * e-mail exists; an existing Firebase user's password is never changed
	 */
	@Transactional
	public NewAdminAccount create(String email, String firstName, String lastName, UUID createdBy) {
		String normalizedEmail = email.trim();
		boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM account WHERE lower(email) = lower(:email))")
			.param("email", normalizedEmail)
			.query(Boolean.class)
			.single();
		if (exists) {
			throw accountAlreadyExists();
		}

		UUID accountId;
		try {
			accountId = jdbc.sql("""
					INSERT INTO account (email, status, created_by, activated_at)
					VALUES (:email, 'ACTIVE', :createdBy, now())
					RETURNING id
					""")
				.param("email", normalizedEmail)
				.param("createdBy", createdBy)
				.query(UUID.class)
				.single();
		}
		catch (DuplicateKeyException ex) {
			// Someone else created an account with the same e-mail at the same moment.
			throw accountAlreadyExists();
		}
		jdbc.sql("""
				INSERT INTO account_role (account_id, role_id, granted_by)
				SELECT :accountId, id, :createdBy FROM role WHERE code = 'ADMIN'
				""")
			.param("accountId", accountId)
			.param("createdBy", createdBy)
			.update();
		jdbc.sql("""
				INSERT INTO account_status_change (account_id, from_status, to_status, changed_by, reason)
				VALUES (:accountId, NULL, 'ACTIVE', :createdBy, 'Created by an administrator')
				""")
			.param("accountId", accountId)
			.param("createdBy", createdBy)
			.update();
		UUID staffProfileId = staffProfiles.create(accountId, firstName.trim(), lastName.trim());

		String password = PasswordGenerator.generate();
		String uid = firebaseUsers.createNew(normalizedEmail, password).orElseThrow(this::accountAlreadyExists);
		deleteUserOnRollback(uid);
		jdbc.sql("""
				INSERT INTO identity_link (account_id, provider, external_uid)
				VALUES (:accountId, :provider, :uid)
				""")
			.param("accountId", accountId)
			.param("provider", AccountLookup.FIREBASE_PROVIDER)
			.param("uid", uid)
			.update();
		return new NewAdminAccount(accountId, staffProfileId, normalizedEmail, password);
	}

	private ApiException accountAlreadyExists() {
		return new ApiException(ErrorCode.ACCOUNT_ALREADY_EXISTS, "An account with this e-mail already exists.");
	}

	private void deleteUserOnRollback(String uid) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					firebaseUsers.delete(uid);
				}
			}
		});
	}

}
