package studioforyou.pl.backend.communication.announcement;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import studioforyou.pl.backend.communication.notification.NotificationDispatcher;

/**
 * Pushes a sent announcement to its recipients' devices in the background, once it is committed:
 * the administrator does not wait for hundreds of pushes, and a failed push is retried by the
 * notification job like any other.
 */
@Component
class AnnouncementPush {

	private final NotificationDispatcher dispatcher;

	AnnouncementPush(NotificationDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Async
	@TransactionalEventListener
	void on(AnnouncementSent event) {
		dispatcher.dispatch(event.notificationIds());
	}

}
