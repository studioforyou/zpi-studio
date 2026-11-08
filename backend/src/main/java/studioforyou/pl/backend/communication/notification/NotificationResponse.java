package studioforyou.pl.backend.communication.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A notification in the recipient's in-app inbox.
 *
 * @param type the notification type, e.g. {@code CLASS_REMINDER}
 * @param role the role it concerns ({@code CLIENT}, {@code TRAINER}, {@code ADMIN}); {@code null} for older notifications
 * @param subjectProfileId the participant it is about when that is not the recipient (a child); otherwise {@code null}
 * @param data what the app needs to open the related screen, e.g. {@code eventId}
 * @param createdAt when the notification became due
 * @param readAt when the recipient read it; {@code null} while unread
 */
public record NotificationResponse(UUID id, String type, String role, UUID subjectProfileId, String title,
		String body, @Schema(example = "{\"eventId\": \"7c1e2a9e-...\"}") Map<String, String> data, Instant createdAt,
		Instant readAt) {

	static NotificationResponse from(NotificationStore.InboxItem item) {
		return new NotificationResponse(item.id(), item.typeCode(), item.role(), item.subjectCustomerId(), item.title(),
				item.body(), item.data(), item.createdAt(), item.readAt());
	}

}
