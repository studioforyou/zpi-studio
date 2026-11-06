package studioforyou.pl.backend.communication.notification;

import java.time.Instant;
import java.util.List;

/**
 * A notification type with the administrator's settings.
 *
 * @param code e.g. {@code CLASS_REMINDER}
 * @param enabled whether the studio sends it
 * @param timed whether it is sent some time before a start; only then {@code leadTimesMinutes} applies
 * @param leadTimesMinutes how long before the start it is sent, in minutes, longest first; empty when not timed
 * @param title the push title template
 * @param body the push text template
 * @param placeholders the {@code {{name}}} values title and body can use
 */
public record NotificationTypeResponse(String code, boolean enabled, boolean timed, List<Integer> leadTimesMinutes,
		String title, String body, List<NotificationType.Placeholder> placeholders, Instant updatedAt) {

	static NotificationTypeResponse from(NotificationStore.TypeSettings settings) {
		NotificationType type = settings.type();
		return new NotificationTypeResponse(type.name(), settings.enabled(), type.timed(), settings.leadTimes(),
				settings.subject(), settings.body(), type.placeholders(), settings.updatedAt());
	}

}
