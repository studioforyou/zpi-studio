package studioforyou.pl.backend.communication.waitlist;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.WaitlistPlaceAllocated;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.Studio;
import studioforyou.pl.backend.configuration.StudioRepository;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;

/** Uses the durable after-commit event registry and the existing notification delivery pipeline. */
@Component
class WaitlistNotifications {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d.MM", Locale.forLanguageTag("pl"));
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("d.MM, HH:mm");

	private final Participations participations;
	private final GroupClassSchedule schedule;
	private final CustomerProfiles customers;
	private final StudioRepository studios;
	private final Notifications notifications;

	WaitlistNotifications(Participations participations, GroupClassSchedule schedule, CustomerProfiles customers,
			StudioRepository studios, Notifications notifications) {
		this.participations = participations;
		this.schedule = schedule;
		this.customers = customers;
		this.studios = studios;
		this.notifications = notifications;
	}

	@Async
	@TransactionalEventListener
	void on(WaitlistPlaceAllocated allocation) {
		Instant now = Instant.now();
		if (!participations.isCurrentWaitlistAllocation(allocation, now)) return;
		var event = schedule.findAllById(List.of(allocation.eventId())).stream()
			.filter(e -> !"CANCELLED".equals(e.status()) && e.startsAt().isAfter(now)).findFirst().orElse(null);
		if (event == null) return;
		ZoneId zone = ZoneId.of(studios.findFirstBy().map(Studio::getTimeZone).orElse("Europe/Warsaw"));
		NotificationType type = allocation.offerExpiresAt() == null
				? NotificationType.WAITLIST_PLACE_GRANTED : NotificationType.WAITLIST_PLACE_OFFERED;
		String name = event.title() != null && !event.title().isBlank() ? event.title()
				: event.serviceTypeName() == null ? "Zajęcia" : event.serviceTypeName();
		Map<String, String> values = new HashMap<>(Map.of(Placeholder.CLASS, name,
				Placeholder.DAY, DAY.format(event.startsAt().atZone(zone)), Placeholder.TIME, TIME.format(event.startsAt().atZone(zone))));
		Map<String, String> data = new HashMap<>(Map.of("eventId", allocation.eventId().toString(),
				"profileId", allocation.customerId().toString(), "waitlistEntryId", allocation.entryId().toString(),
				"eventName", name, "eventStartsAt", event.startsAt().toString()));
		if (allocation.offerExpiresAt() != null) {
			values.put(Placeholder.CONFIRM_UNTIL, DEADLINE.format(allocation.offerExpiresAt().atZone(zone)));
			data.put("offerExpiresAt", allocation.offerExpiresAt().toString());
		}
		else {
			data.put("reservationId", allocation.reservationId().toString());
		}
		List<NotificationRequest> requests = new ArrayList<>();
		for (var recipient : customers.notificationRecipients(List.of(allocation.customerId()))) {
			requests.add(new NotificationRequest(type, recipient.accountId(), NotificationRole.CLIENT,
					recipient.forChild() ? recipient.profileId() : null, recipient.guardian() ? recipient.firstName() : null,
					type.name() + ":" + allocation.entryId() + ":" + recipient.accountId(), values, data, now));
		}
		notifications.notifyNow(requests);
	}

}
