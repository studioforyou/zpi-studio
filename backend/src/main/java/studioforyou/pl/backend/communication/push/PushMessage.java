package studioforyou.pl.backend.communication.push;

import java.util.Map;

/**
 * A push notification for one device.
 *
 * @param token the FCM registration token of the device
 * @param title the notification title shown by the system
 * @param body the notification text
 * @param data key-value data for the app, e.g. what to open when the notification is tapped
 * @param tag identifies the notification on the device: a push with the same tag replaces the
 * one shown before, e.g. an updated notification about the unread messages of a conversation
 */
public record PushMessage(String token, String title, String body, Map<String, String> data, String tag) {

	public PushMessage {
		data = Map.copyOf(data);
	}

}
