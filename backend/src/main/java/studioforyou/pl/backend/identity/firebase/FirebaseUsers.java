package studioforyou.pl.backend.identity.firebase;

import java.util.Optional;

/**
 * Manages users in Firebase Authentication on behalf of the backend (Firebase Admin SDK).
 */
public interface FirebaseUsers {

	/**
	 * Creates a Firebase user with the given e-mail and password, marked as verified. If a user
	 * with this e-mail already exists, sets its password instead: whoever opened the invitation
	 * link controls the mailbox.
	 */
	FirebaseUser createOrUpdate(String email, String password);

	/**
	 * Creates a new Firebase user with the given e-mail and password, marked as verified. Unlike
	 * {@link #createOrUpdate}, never changes an existing user's password.
	 *
	 * @return the new user's UID, or empty if a user with this e-mail already exists
	 */
	Optional<String> createNew(String email, String password);

	/** Deletes a user created by {@link #createOrUpdate} or {@link #createNew}, e.g. when the database change is rolled back. */
	void delete(String uid);

	/**
	 * @param uid the Firebase user's UID
	 * @param created whether the user was created now (rather than already existing)
	 */
	record FirebaseUser(String uid, boolean created) {
	}

}
