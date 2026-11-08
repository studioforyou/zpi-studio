package studioforyou.pl.backend.communication.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.communication.device.DeviceTokens;
import studioforyou.pl.backend.communication.notification.NotificationStore.DeliveryStatus;
import studioforyou.pl.backend.communication.notification.NotificationStore.Outgoing;
import studioforyou.pl.backend.communication.push.PushMessage;
import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;

/**
 * Sends stored notifications to all active devices of their recipients and records every attempt
 * in {@code notification_delivery}. A failure never throws: the notification stays in the inbox
 * and the notification job tries again.
 */
@Component
public class NotificationDispatcher {

	/** How many failed attempts a notification gets before the job stops trying. */
	static final int MAX_FAILURES = 3;

	/** Notifications older than this are not retried: a late reminder is no longer useful. */
	static final Duration RETRY_WINDOW = Duration.ofHours(12);

	/** Leaves time for the request that created a notification to send it itself. */
	static final Duration RETRY_DELAY = Duration.ofMinutes(2);

	private final NotificationStore store;

	private final DeviceTokens devices;

	private final PushSender pushSender;

	NotificationDispatcher(NotificationStore store, DeviceTokens devices, PushSender pushSender) {
		this.store = store;
		this.devices = devices;
		this.pushSender = pushSender;
	}

	/** Sends the given notifications now; returns how many reached at least one device. */
	public int dispatch(Collection<UUID> notificationIds) {
		int delivered = 0;
		for (Outgoing notification : store.findOutgoing(notificationIds)) {
			if (send(notification)) {
				delivered++;
			}
		}
		return delivered;
	}

	/** Sends again the recent notifications that have not reached a device yet; returns how many were tried. */
	public int retryUndelivered(Instant now) {
		List<UUID> ids = store.findUndelivered(now, now.minus(RETRY_WINDOW), now.minus(RETRY_DELAY),
				MAX_FAILURES);
		dispatch(ids);
		return ids.size();
	}

	private boolean send(Outgoing notification) {
		List<String> tokens = devices.activeTokens(notification.recipientId());
		if (tokens.isEmpty()) {
			store.recordDelivery(notification.id(), DeliveryStatus.NO_DEVICE, null, null);
			return false;
		}
		Map<String, String> data = new HashMap<>(notification.data());
		data.put("notificationId", notification.id().toString());
		data.put("type", notification.typeCode());
		boolean sent = false;
		for (String token : tokens) {
			PushResult result = pushSender.send(new PushMessage(token, notification.title(),
					notification.body() == null ? "" : notification.body(), data));
			switch (result.outcome()) {
				case SENT -> {
					store.recordDelivery(notification.id(), DeliveryStatus.SENT, result.messageId(), null);
					sent = true;
				}
				case INVALID_TOKEN -> {
					devices.revokeInvalid(token);
					store.recordDelivery(notification.id(), DeliveryStatus.TOKEN_INVALID, null, result.error());
				}
				case FAILED -> store.recordDelivery(notification.id(), DeliveryStatus.FAILED, null, result.error());
			}
		}
		return sent;
	}

}
