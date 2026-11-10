package studioforyou.pl.backend.communication.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import studioforyou.pl.backend.communication.messaging.MessagingService.NewMessage;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;

/**
 * Notifies the other participants of a conversation about a new message ({@code NEW_MESSAGE}),
 * right after it is saved: one notification per message and recipient. Tapping it opens the
 * conversation in the role it concerns.
 */
@Component
class MessageNotifications {

	/** How much of the message the notification shows. */
	static final int PREVIEW_LENGTH = 100;

	private final MessagingService messaging;

	private final Notifications notifications;

	MessageNotifications(MessagingService messaging, Notifications notifications) {
		this.messaging = messaging;
		this.notifications = notifications;
	}

	@Async
	@TransactionalEventListener
	void on(MessageSent event) {
		messaging.newMessage(event).ifPresent((message) -> notifications.notifyNow(requests(event, message)));
	}

	private static List<NotificationRequest> requests(MessageSent event, NewMessage message) {
		String conversation = (message.chatTitle() != null) ? message.chatTitle() : message.senderName();
		String sender = (message.chatTitle() != null) ? message.chatTitle() + " · " + message.senderName()
				: message.senderName();
		Map<String, String> values = Map.of(Placeholder.SENDER, sender, Placeholder.CONVERSATION, conversation,
				Placeholder.TEXT, preview(message.body()));
		Instant now = Instant.now();
		List<NotificationRequest> requests = new ArrayList<>();
		for (Map.Entry<UUID, NotificationRole> recipient : message.recipients().entrySet()) {
			requests.add(new NotificationRequest(NotificationType.NEW_MESSAGE, recipient.getKey(), recipient.getValue(),
					null, null, NotificationType.NEW_MESSAGE.name() + ":" + event.messageId() + ":" + recipient.getKey(),
					values, Map.of("conversationId", message.conversationId().toString(), "messageId",
							event.messageId().toString(), "role", recipient.getValue().name()),
					now));
		}
		return requests;
	}

	/** The message on one line, cut at a word to at most {@link #PREVIEW_LENGTH} characters. */
	static String preview(String body) {
		String text = body.strip().replaceAll("\\s+", " ");
		if (text.length() <= PREVIEW_LENGTH) {
			return text;
		}
		String cut = text.substring(0, PREVIEW_LENGTH - 1);
		int space = cut.lastIndexOf(' ');
		if (space > PREVIEW_LENGTH / 2) {
			cut = cut.substring(0, space);
		}
		return cut.stripTrailing() + "…";
	}

}
