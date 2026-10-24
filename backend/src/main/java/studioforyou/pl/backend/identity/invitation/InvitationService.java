package studioforyou.pl.backend.identity.invitation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.identity.AccountLookup;
import studioforyou.pl.backend.identity.AccountInvited;
import studioforyou.pl.backend.identity.AccountStatus;
import studioforyou.pl.backend.identity.firebase.FirebaseUsers;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * E-mail invitations ({@code account_invitation}). Creating one makes an {@code INVITED} account with
 * its profile and sends the invitation e-mail; accepting it creates the Firebase user, links it to the
 * account and activates the account.
 */
@Service
@EnableConfigurationProperties(InvitationProperties.class)
public class InvitationService {

	private final JdbcClient jdbc;

	private final FirebaseUsers firebaseUsers;

	private final CustomerProfiles customerProfiles;

	private final StaffProfiles staffProfiles;

	private final ApplicationEventPublisher events;

	private final InvitationProperties properties;

	public InvitationService(JdbcClient jdbc, FirebaseUsers firebaseUsers, CustomerProfiles customerProfiles,
			StaffProfiles staffProfiles, ApplicationEventPublisher events, InvitationProperties properties) {
		this.jdbc = jdbc;
		this.firebaseUsers = firebaseUsers;
		this.customerProfiles = customerProfiles;
		this.staffProfiles = staffProfiles;
		this.events = events;
		this.properties = properties;
	}

	/**
	 * Creates an {@code INVITED} account with the role and a profile (customer profile for a client,
	 * staff profile for a trainer), records the invitation and sends the invitation e-mail. If the
	 * e-mail cannot be sent, nothing is saved.
	 *
	 * @param invitedBy the account of the staff member sending the invitation
	 * @throws ApiException {@code ACCOUNT_ALREADY_EXISTS} if an account with this e-mail exists,
	 * {@code EMAIL_NOT_SENT} if the e-mail provider failed
	 */
	@Transactional
	public NewInvitation invite(String email, String firstName, String lastName, InvitedRole role,
			UUID invitedBy) {
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
					INSERT INTO account (email, status, created_by) VALUES (:email, 'INVITED', :invitedBy)
					RETURNING id
					""")
				.param("email", normalizedEmail)
				.param("invitedBy", invitedBy)
				.query(UUID.class)
				.single();
		}
		catch (DuplicateKeyException ex) {
			// Someone else invited the same e-mail at the same moment.
			throw accountAlreadyExists();
		}
		jdbc.sql("""
				INSERT INTO account_role (account_id, role_id, granted_by)
				SELECT :accountId, id, :invitedBy FROM role WHERE code = :role
				""")
			.param("accountId", accountId)
			.param("invitedBy", invitedBy)
			.param("role", role.name())
			.update();
		jdbc.sql("""
				INSERT INTO account_status_change (account_id, from_status, to_status, changed_by)
				VALUES (:accountId, NULL, 'INVITED', :invitedBy)
				""")
			.param("accountId", accountId)
			.param("invitedBy", invitedBy)
			.update();
		switch (role) {
			case CLIENT -> customerProfiles.createOwnProfile(accountId, firstName.trim(), lastName.trim(), invitedBy);
			case TRAINER -> staffProfiles.create(accountId, firstName.trim(), lastName.trim());
		}

		String token = InvitationTokens.generate();
		OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.validFor());
		UUID invitationId = jdbc.sql("""
				INSERT INTO account_invitation (account_id, token_hash, sent_by, expires_at)
				VALUES (:accountId, :tokenHash, :invitedBy, :expiresAt)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("tokenHash", InvitationTokens.hash(token))
			.param("invitedBy", invitedBy)
			.param("expiresAt", expiresAt)
			.query(UUID.class)
			.single();

		events.publishEvent(new AccountInvited(normalizedEmail, firstName.trim(), role.name(), link(token), expiresAt));
		return new NewInvitation(invitationId, accountId, normalizedEmail, expiresAt);
	}

	/**
	 * Returns the invitation behind the token.
	 *
	 * @throws ApiException {@code INVITATION_NOT_FOUND}, {@code INVITATION_EXPIRED} or
	 * {@code INVITATION_ALREADY_USED}
	 */
	@Transactional(readOnly = true)
	public InvitationDetails find(String token) {
		Invitation invitation = findValid(token, false);
		String displayName = customerProfiles.findOwnProfile(invitation.accountId())
			.map(CustomerProfiles.ProfileSummary::displayName)
			.or(() -> staffProfiles.findByAccount(invitation.accountId()).map(StaffProfiles.StaffSummary::displayName))
			.orElse(null);
		return new InvitationDetails(invitation.email(), displayName, findRoleCodes(invitation.accountId()),
				invitation.expiresAt());
	}

	/**
	 * Accepts the invitation: creates (or updates) the Firebase user with the given password, links it
	 * to the account, activates the account and marks the invitation as used. If the transaction rolls
	 * back, a Firebase user created here is deleted again.
	 *
	 * @return the activated account's e-mail, to sign in with
	 * @throws ApiException as {@link #find(String)}
	 */
	@Transactional
	public String accept(String token, String password) {
		Invitation invitation = findValid(token, true);
		FirebaseUsers.FirebaseUser user = firebaseUsers.createOrUpdate(invitation.email(), password);
		if (user.created()) {
			deleteUserOnRollback(user.uid());
		}

		jdbc.sql("""
				INSERT INTO identity_link (account_id, provider, external_uid)
				VALUES (:accountId, :provider, :uid)
				ON CONFLICT (account_id, provider) DO UPDATE SET external_uid = EXCLUDED.external_uid
				""")
			.param("accountId", invitation.accountId())
			.param("provider", AccountLookup.FIREBASE_PROVIDER)
			.param("uid", user.uid())
			.update();
		jdbc.sql("""
				UPDATE account SET status = 'ACTIVE', activated_at = now(), updated_at = now()
				WHERE id = :accountId
				""")
			.param("accountId", invitation.accountId())
			.update();
		jdbc.sql("""
				INSERT INTO account_status_change (account_id, from_status, to_status, reason)
				VALUES (:accountId, 'INVITED', 'ACTIVE', 'Invitation accepted')
				""")
			.param("accountId", invitation.accountId())
			.update();
		jdbc.sql("UPDATE account_invitation SET used_at = now() WHERE id = :id")
			.param("id", invitation.id())
			.update();
		// Older invitations of the same account must not work any more.
		jdbc.sql("""
				UPDATE account_invitation SET revoked_at = now()
				WHERE account_id = :accountId AND id <> :id AND used_at IS NULL AND revoked_at IS NULL
				""")
			.param("accountId", invitation.accountId())
			.param("id", invitation.id())
			.update();
		return invitation.email();
	}

	private Invitation findValid(String token, boolean lock) {
		Invitation invitation = jdbc.sql("""
				SELECT i.id, i.account_id, a.email, a.status, i.expires_at, i.used_at, i.revoked_at
				FROM account_invitation i
				JOIN account a ON a.id = i.account_id
				WHERE i.token_hash = :tokenHash
				""" + (lock ? "FOR UPDATE OF i, a" : ""))
			.param("tokenHash", InvitationTokens.hash(token))
			.query(Invitation.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.INVITATION_NOT_FOUND, "This invitation does not exist."));
		if (invitation.usedAt() != null || invitation.revokedAt() != null
				|| invitation.status() != AccountStatus.INVITED) {
			throw new ApiException(ErrorCode.INVITATION_ALREADY_USED,
					"This invitation has already been used or was withdrawn.");
		}
		if (!invitation.expiresAt().isAfter(OffsetDateTime.now())) {
			throw new ApiException(ErrorCode.INVITATION_EXPIRED, "This invitation has expired.");
		}
		return invitation;
	}

	private static ApiException accountAlreadyExists() {
		return new ApiException(ErrorCode.ACCOUNT_ALREADY_EXISTS, "An account with this e-mail already exists.");
	}

	private String link(String token) {
		return properties.linkBaseUrl() + (properties.linkBaseUrl().contains("?") ? "&" : "?") + "token=" + token;
	}

	private List<String> findRoleCodes(UUID accountId) {
		return jdbc.sql("""
				SELECT r.code
				FROM account_role ar
				JOIN role r ON r.id = ar.role_id
				WHERE ar.account_id = :accountId
				ORDER BY r.code
				""")
			.param("accountId", accountId)
			.query(String.class)
			.list();
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

	record Invitation(UUID id, UUID accountId, String email, AccountStatus status, OffsetDateTime expiresAt,
			OffsetDateTime usedAt, OffsetDateTime revokedAt) {
	}

}
