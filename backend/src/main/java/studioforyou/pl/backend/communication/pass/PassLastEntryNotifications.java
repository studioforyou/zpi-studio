package studioforyou.pl.backend.communication.pass;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.membership.ExpiringPasses;
import studioforyou.pl.backend.membership.PassLastEntryUsed;

/**
 * Tells the participant that a booking used the last entry of a pass
 * ({@code PASS_LAST_ENTRY_USED}), right after the booking commits. Sent once per pass: taking the
 * last entry again after a cancellation notifies nobody. Not sent when the participant already
 * has a following pass.
 */
@Component
class PassLastEntryNotifications {

	private final Notifications notifications;

	private final ExpiringPasses passes;

	private final PassNotificationRequests requests;

	PassLastEntryNotifications(Notifications notifications, ExpiringPasses passes,
			PassNotificationRequests requests) {
		this.notifications = notifications;
		this.passes = passes;
		this.requests = requests;
	}

	@Async
	@TransactionalEventListener
	void on(PassLastEntryUsed event) {
		Instant now = Instant.now();
		LocalDate today = now.atZone(requests.studioZone()).toLocalDate();
		if (passes.isFollowed(event.passId(), today)) {
			return;
		}
		notifications.notifyNow(requests.forPass(NotificationType.PASS_LAST_ENTRY_USED, event.passId(),
				event.customerId(), event.productName(), event.validUntil(), Map.of(),
				NotificationType.PASS_LAST_ENTRY_USED.name() + ":" + event.passId(), now));
	}

}
