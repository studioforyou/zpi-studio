package studioforyou.pl.backend.communication.push;

import java.util.Map;

/**
 * A push notification for one device.
 *
 * @param token the FCM registration token of the device
 * @param title the notification title shown by the system
 * @param body the notification text
 * @param data key-value data for the app, e.g. what to open when the notification is tapped
 */
public record PushMessage(String token, String title, String body, Map<String, String> data) {

	public PushMessage {
		data = Map.copyOf(data);
	}

}
