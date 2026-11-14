package studioforyou.pl.backend.communication.substitution;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.scheduling.SubstitutionOffers;
import studioforyou.pl.backend.scheduling.SubstitutionOpened;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** One inbox item and its push, after commit and only while the offer remains current. */
@Component
class SubstitutionNotifications {

	private final SubstitutionOffers offers;
	private final Notifications notifications;
	private final StudioSettings studio;

	SubstitutionNotifications(SubstitutionOffers offers, Notifications notifications, StudioSettings studio) {
		this.offers = offers; this.notifications = notifications; this.studio = studio;
	}

	@Async
	@TransactionalEventListener
	void opened(SubstitutionOpened event) {
		this.offers.notification(event.requestId(), event.version()).ifPresent((offer) -> {
			var start = offer.startsAt().atZone(this.studio.zone());
			var end = offer.endsAt().atZone(this.studio.zone());
			var time = DateTimeFormatter.ofPattern("HH:mm");
			Map<String, String> values = Map.of(Placeholder.CLASS, offer.title(), Placeholder.ROOM, offer.rooms(),
					Placeholder.DAY, DateTimeFormatter.ofPattern("EEE, d.MM", Locale.forLanguageTag("pl")).format(start),
					Placeholder.TIME, time.format(start) + "–" + time.format(end));
			Map<String, String> data = Map.of("requestId", offer.requestId().toString(), "title", offer.title(),
					"rooms", offer.rooms(), "startsAt", offer.startsAt().toString(), "endsAt", offer.endsAt().toString(), "role", "TRAINER");
			List<NotificationRequest> requests = offer.recipientAccountIds().stream().map((account) -> new NotificationRequest(
					NotificationType.SUBSTITUTION_AVAILABLE, account, NotificationRole.TRAINER, null, null,
					"SUBSTITUTION_AVAILABLE:" + offer.requestId() + ":" + offer.version() + ":" + account,
					values, data, Instant.now())).toList();
			this.notifications.notifyNow(requests);
		});
	}
}
