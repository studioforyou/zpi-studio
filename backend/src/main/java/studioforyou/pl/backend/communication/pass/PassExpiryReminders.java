package studioforyou.pl.backend.communication.pass;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationStore;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.communication.reminder.ClassReminders;
import studioforyou.pl.backend.membership.ExpiringPasses;
import studioforyou.pl.backend.membership.ExpiringPasses.ExpiringPass;

/**
 * Reminders that a pass is about to expire ({@code PASS_EXPIRING}). A pass has a last day but no
 * hour, so the reminder times the administrator set (e.g. 3 days and 1 day) count back from
 * {@link #ANCHOR} on that day in the studio time zone; nobody gets a reminder at night. Run by the
 * notification job every few minutes.
 * <p>
 * A reminder is due from its moment for {@link #GRACE}, so a missed job run is caught up. It is not
 * sent for a pass assigned after the moment, for a pass with no entries left, or when the
 * participant already holds a following pass. Extending the pass moves the reminders to the new
 * last day, which counts as a new reminder.
 */
@Component
public class PassExpiryReminders {

	/** The time of the last day the reminder times count back from. */
	static final LocalTime ANCHOR = LocalTime.of(9, 0);

	/** How long after the moment a reminder is still sent; longer than the job interval. */
	static final Duration GRACE = Duration.ofMinutes(30);

	private final NotificationStore settings;

	private final Notifications notifications;

	private final ExpiringPasses passes;

	private final PassNotificationRequests requests;

	PassExpiryReminders(NotificationStore settings, Notifications notifications, ExpiringPasses passes,
			PassNotificationRequests requests) {
		this.settings = settings;
		this.notifications = notifications;
		this.passes = passes;
		this.requests = requests;
	}

	/** Creates and sends the reminders due at {@code now}; returns how many were created. */
	public int sendDue(Instant now) {
		NotificationStore.TypeSettings type = settings.settings(NotificationType.PASS_EXPIRING);
		if (!type.enabled() || type.leadTimes().isEmpty()) {
			return 0;
		}
		ZoneId zone = requests.studioZone();
		Duration longest = Duration.ofMinutes(type.leadTimes().getFirst());
		LocalDate from = now.atZone(zone).toLocalDate();
		LocalDate to = now.plus(longest).plus(GRACE).atZone(zone).toLocalDate();
		List<NotificationRequest> due = new ArrayList<>();
		for (ExpiringPass pass : passes.findExpiringBetween(from, to)) {
			Instant anchor = pass.validUntil().atTime(ANCHOR).atZone(zone).toInstant();
			for (int minutes : type.leadTimes()) {
				Instant moment = anchor.minus(Duration.ofMinutes(minutes));
				if (isDue(moment, pass.createdAt(), now)) {
					due.addAll(requests.forPass(NotificationType.PASS_EXPIRING, pass.id(), pass.customerId(),
							pass.productName(), pass.validUntil(),
							Map.of(Placeholder.LEAD_TIME, ClassReminders.leadTime(minutes)),
							NotificationType.PASS_EXPIRING.name() + ":" + pass.id() + ":" + pass.validUntil() + ":"
									+ minutes,
							moment));
				}
			}
		}
		return due.isEmpty() ? 0 : notifications.notifyNow(due);
	}

	/** Whether the reminder at {@code moment} is due at {@code now} for a pass assigned at {@code createdAt}. */
	static boolean isDue(Instant moment, Instant createdAt, Instant now) {
		return !now.isBefore(moment) && now.isBefore(moment.plus(GRACE)) && !createdAt.isAfter(moment);
	}

}
