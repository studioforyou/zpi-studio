package studioforyou.pl.backend.identity.firebase;

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

	/** Deletes a user created by {@link #createOrUpdate}, e.g. when the database change is rolled back. */
	void delete(String uid);

	/**
	 * @param uid the Firebase user's UID
	 * @param created whether the user was created now (rather than already existing)
	 */
	record FirebaseUser(String uid, boolean created) {
	}

}
