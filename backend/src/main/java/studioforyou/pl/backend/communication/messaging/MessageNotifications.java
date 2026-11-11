package studioforyou.pl.backend.communication.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import studioforyou.pl.backend.communication.messaging.MessagingService.NewMessage;
import studioforyou.pl.backend.communication.messaging.MessagingService.UnreadMessages;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;

/**
 * Notifies the other participants of a conversation about new messages ({@code NEW_MESSAGE}),
 * right after each one is saved. A recipient has one notification per conversation for the
 * messages it has not read yet: each new message updates it (how many, from whom, the latest
 * text), moves it to the top, makes it unread and sends it again, replacing the push shown before.
 * Once the recipient reads the conversation the notification is read too, and the next message
 * starts a new one. Tapping it opens the conversation in the role it concerns.
 */
@Component
class MessageNotifications {

	/** How much of the message the notification shows. */
	static final int PREVIEW_LENGTH = 100;

	/** How many writers {@code {{od}}} names before it says how many others there are. */
	static final int NAMED_SENDERS = 2;

	/** The data key with the conversation the notification is about. */
	static final String CONVERSATION_ID = "conversationId";

	private final MessagingService messaging;

	private final Notifications notifications;

	MessageNotifications(MessagingService messaging, Notifications notifications) {
		this.messaging = messaging;
		this.notifications = notifications;
	}

	@Async
	@TransactionalEventListener
	void on(MessageSent event) {
		messaging.newMessage(event).ifPresent((message) -> notifications.notifyNowOrUpdate(requests(message)));
	}

	private static List<NotificationRequest> requests(NewMessage message) {
		List<NotificationRequest> requests = new ArrayList<>();
		for (Map.Entry<UUID, UnreadMessages> entry : message.recipients().entrySet()) {
			UUID account = entry.getKey();
			UnreadMessages unread = entry.getValue();
			String senders = senders(unread.senders());
			String sender = (message.chatTitle() != null) ? message.chatTitle() + " · " + senders : senders;
			String conversation = (message.chatTitle() != null) ? message.chatTitle() : senders;
			Map<String, String> values = Map.of(Placeholder.SENDER, sender, Placeholder.CONVERSATION, conversation,
					Placeholder.COUNT, count(unread.count()), Placeholder.TEXT, preview(unread.latestBody()));
			// The key stays the same until the recipient reads the conversation: one notification for
			// all the messages it has not read.
			String dedupKey = NotificationType.NEW_MESSAGE.name() + ":" + message.conversationId() + ":" + account + ":"
					+ unread.firstId();
			requests.add(new NotificationRequest(NotificationType.NEW_MESSAGE, account, unread.role(), null, null,
					dedupKey, values,
					Map.of(CONVERSATION_ID, message.conversationId().toString(), "messageId",
							unread.latestId().toString(), "role", unread.role().name()),
					unread.latestAt()));
		}
		return requests;
	}

	/** Who wrote the messages, the latest writer first, e.g. "Anna Nowak, Ewa Lis i 2 inne osoby". */
	static String senders(List<String> names) {
		if (names.size() <= NAMED_SENDERS) {
			return String.join(", ", names);
		}
		int others = names.size() - NAMED_SENDERS;
		return String.join(", ", names.subList(0, NAMED_SENDERS)) + " i " + others + " "
				+ polish(others, "inna osoba", "inne osoby", "innych osób");
	}

	/** How many messages, e.g. "1 nowa wiadomość", "3 nowe wiadomości", "5 nowych wiadomości". */
	static String count(int count) {
		return count + " " + polish(count, "nowa wiadomość", "nowe wiadomości", "nowych wiadomości");
	}

	/** The Polish plural form for a number: 1, 2-4 (but not 12-14), the rest. */
	private static String polish(int count, String one, String few, String many) {
		if (count == 1) {
			return one;
		}
		int lastDigit = count % 10;
		int lastTwo = count % 100;
		return (lastDigit >= 2 && lastDigit <= 4 && (lastTwo < 12 || lastTwo > 14)) ? few : many;
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
