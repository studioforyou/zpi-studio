package studioforyou.pl.backend.communication.push;

import java.io.IOException;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends push notifications through Firebase Cloud Messaging (HTTP v1 API) with the Firebase Admin
 * SDK. Credentials come from Application Default Credentials: the backend's service account on
 * Cloud Run, a key file in {@code GOOGLE_APPLICATION_CREDENTIALS} locally. The SDK is initialized
 * on first use, so the application starts without credentials.
 */
class FcmPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

	/** Separate from the identity module's Firebase app, so the modules do not share SDK state. */
	private static final String APP_NAME = "studioforyou-messaging";

	private final String projectId;

	private volatile FirebaseMessaging messaging;

	FcmPushSender(String projectId) {
		this.projectId = projectId;
	}

	@Override
	public PushResult send(PushMessage message) {
		Message fcmMessage = Message.builder()
			.setToken(message.token())
			.setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
			.putAllData(message.data())
			.setAndroidConfig(AndroidConfig.builder()
				.setPriority(AndroidConfig.Priority.HIGH)
				.setNotification(AndroidNotification.builder().setTag(message.tag()).build())
				.build())
			.setApnsConfig(ApnsConfig.builder()
				.putHeader("apns-collapse-id", message.tag())
				.setAps(Aps.builder().setSound("default").build())
				.build())
			.build();
		try {
			return PushResult.sent(messaging().send(fcmMessage));
		}
		catch (FirebaseMessagingException ex) {
			MessagingErrorCode code = ex.getMessagingErrorCode();
			if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
				return PushResult.invalidToken(code + ": " + ex.getMessage());
			}
			log.warn("FCM did not send a push notification: {} {}", code, ex.getMessage());
			return PushResult.failed((code != null ? code + ": " : "") + ex.getMessage());
		}
		catch (RuntimeException ex) {
			log.warn("FCM did not send a push notification", ex);
			return PushResult.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	private FirebaseMessaging messaging() {
		FirebaseMessaging result = this.messaging;
		if (result == null) {
			synchronized (this) {
				result = this.messaging;
				if (result == null) {
					result = FirebaseMessaging.getInstance(initializeApp());
					this.messaging = result;
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
					"No Google credentials for Firebase Cloud Messaging (set GOOGLE_APPLICATION_CREDENTIALS locally)", ex);
		}
	}

}
