package studioforyou.pl.backend.communication.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A notification to create for one account. The title and text come from the type's template,
 * filled with {@code values}.
 *
 * @param type the notification type; nothing is created while the administrator has it switched off
 * @param recipientId the account that gets the notification
 * @param role the role the notification concerns
 * @param subjectCustomerId the participant it is about when that is not the recipient, e.g. a child
 * @param subjectName the first name of that participant, shown in the notification
 * @param dedupKey a key unique for this notification and recipient, so a repeated job or event
 * does not create it twice, e.g. {@code CLASS_REMINDER:<reservation>:<minutes>:<account>}
 * @param values the template placeholder values
 * @param data what the app needs to open the right screen, e.g. {@code eventId}; sent with the push
 * @param scheduledFor when the notification is due
 */
public record NotificationRequest(NotificationType type, UUID recipientId, NotificationRole role,
		@Nullable UUID subjectCustomerId, @Nullable String subjectName, String dedupKey, Map<String, String> values,
		Map<String, String> data, Instant scheduledFor) {

	public NotificationRequest {
		values = Map.copyOf(values);
		data = Map.copyOf(data);
	}

}
