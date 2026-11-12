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
import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.WaitlistPlaceAllocated;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;

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

	private final Participations participations;
	private final CustomerProfiles customers;
	private final GroupClassSchedule schedule;

	NotificationDispatcher(NotificationStore store, DeviceTokens devices, PushSender pushSender,
			Participations participations, CustomerProfiles customers, GroupClassSchedule schedule) {
		this.store = store;
		this.devices = devices;
		this.pushSender = pushSender;
		this.participations = participations;
		this.customers = customers;
		this.schedule = schedule;
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
		if (!currentWaitlistAllocation(notification)) {
			store.recordDelivery(notification.id(), DeliveryStatus.SKIPPED, null, "The waiting-list allocation is no longer current.");
			return false;
		}
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

	/** A delayed retry must not invite someone to an expired offer or another person's profile. */
	private boolean currentWaitlistAllocation(Outgoing notification) {
		if (!notification.typeCode().equals("WAITLIST_PLACE_OFFERED")
				&& !notification.typeCode().equals("WAITLIST_PLACE_GRANTED")) return true;
		WaitlistPlaceAllocated allocation;
		try {
			Map<String, String> data = notification.data();
			allocation = new WaitlistPlaceAllocated(UUID.fromString(data.get("waitlistEntryId")),
					UUID.fromString(data.get("profileId")), UUID.fromString(data.get("eventId")),
					data.containsKey("reservationId") ? UUID.fromString(data.get("reservationId")) : null,
					data.containsKey("offerExpiresAt") ? Instant.parse(data.get("offerExpiresAt")) : null);
		}
		catch (IllegalArgumentException | java.time.DateTimeException | NullPointerException ex) {
			return false;
		}
		Instant now = Instant.now();
		return participations.isCurrentWaitlistAllocation(allocation, now)
				&& customers.notificationRecipients(List.of(allocation.customerId())).stream()
					.anyMatch(recipient -> recipient.accountId().equals(notification.recipientId()))
				&& schedule.findAllById(List.of(allocation.eventId())).stream()
					.anyMatch(event -> !"CANCELLED".equals(event.status()) && event.startsAt().isAfter(now));
	}

}
