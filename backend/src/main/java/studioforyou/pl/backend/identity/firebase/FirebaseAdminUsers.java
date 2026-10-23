package studioforyou.pl.backend.identity.firebase;

import java.io.IOException;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link FirebaseUsers} backed by the Firebase Admin SDK. Credentials come from Application Default
 * Credentials: the service account on Cloud Run, a key file in {@code GOOGLE_APPLICATION_CREDENTIALS}
 * locally. The SDK is initialized on first use, so the application starts without credentials.
 */
@Component
class FirebaseAdminUsers implements FirebaseUsers {

	private static final String APP_NAME = "studioforyou-backend";

	private final String projectId;

	private volatile FirebaseAuth auth;

	FirebaseAdminUsers(@Value("${FIREBASE_PROJECT_ID}") String projectId) {
		this.projectId = projectId;
	}

	@Override
	public FirebaseUser createOrUpdate(String email, String password) {
		try {
			UserRecord existing = auth().getUserByEmail(email);
			auth().updateUser(new UserRecord.UpdateRequest(existing.getUid()).setPassword(password)
				.setEmailVerified(true)
				.setDisabled(false));
			return new FirebaseUser(existing.getUid(), false);
		}
		catch (FirebaseAuthException ex) {
			if (ex.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
				throw new IllegalStateException("Firebase user lookup failed for " + email, ex);
			}
		}
		try {
			UserRecord created = auth().createUser(
					new UserRecord.CreateRequest().setEmail(email).setPassword(password).setEmailVerified(true));
			return new FirebaseUser(created.getUid(), true);
		}
		catch (FirebaseAuthException ex) {
			throw new IllegalStateException("Could not create the Firebase user " + email, ex);
		}
	}

	@Override
	public void delete(String uid) {
		try {
			auth().deleteUser(uid);
		}
		catch (FirebaseAuthException ex) {
			throw new IllegalStateException("Could not delete the Firebase user " + uid, ex);
		}
	}

	private FirebaseAuth auth() {
		FirebaseAuth result = this.auth;
		if (result == null) {
			synchronized (this) {
				result = this.auth;
				if (result == null) {
					result = FirebaseAuth.getInstance(initializeApp());
					this.auth = result;
				}
			}
		}
		return result;
	}

	private FirebaseApp initializeApp() {
		try {
			FirebaseOptions options = FirebaseOptions.builder()
				.setCredentials(GoogleCredentials.getApplicationDefault())
				.setProjectId(this.projectId)
				.build();
			return FirebaseApp.initializeApp(options, APP_NAME);
		}
		catch (IOException ex) {
			throw new IllegalStateException(
					"No Google credentials for the Firebase Admin SDK (set GOOGLE_APPLICATION_CREDENTIALS locally)", ex);
		}
	}

}
