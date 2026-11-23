package studioforyou.pl.backend.communication.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.communication.notification.NotificationRole;

/** Responses of the history of sent notifications. */
public final class NotificationHistoryDtos {

	private NotificationHistoryDtos() {
	}

	/**
	 * One notification sent to one account.
	 *
	 * @param body {@code null} when {@link #bodyHidden()}: chat message contents are never shown
	 * @param about the participant it concerns when the recipient gets it for them, e.g. a child
	 * @param author who sent it, for announcements; {@code null} for automatic notifications
	 */
	public record HistoryItem(UUID id, String type, UUID recipientId, String recipientName,
			@Nullable NotificationRole recipientRole, @Nullable String about, String title, @Nullable String body,
			boolean bodyHidden, boolean marketing, @Nullable UUID announcementId, @Nullable String author,
			Instant sentAt, @Nullable Instant readAt, SendStatus status) {
	}

	/** A push attempt: {@code status} is SENT, FAILED, TOKEN_INVALID, NO_DEVICE or SKIPPED. */
	public record DeliveryAttempt(Instant attemptedAt, String channel, String status, @Nullable String error) {
	}

	public record HistoryDetails(HistoryItem notification, List<DeliveryAttempt> deliveries) {
	}

}
