package studioforyou.pl.backend.communication.timeoff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.Studio;
import studioforyou.pl.backend.configuration.StudioRepository;
import studioforyou.pl.backend.staff.TimeOffChanged;

/**
 * Tells a trainer what the studio did with their time off, right after the change commits:
 * approved (also time off the studio recorded itself), rejected or cancelled. Tapping the
 * notification opens the trainer's availability calendar.
 */
@Component
class TimeOffNotifications {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d.MM");

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm");

	private final Notifications notifications;

	private final StudioRepository studios;

	TimeOffNotifications(Notifications notifications, StudioRepository studios) {
		this.notifications = notifications;
		this.studios = studios;
	}

	@Async
	@TransactionalEventListener
	void on(TimeOffChanged event) {
		NotificationType type = switch (event.change()) {
			case APPROVED -> NotificationType.TIME_OFF_APPROVED;
			case REJECTED -> NotificationType.TIME_OFF_REJECTED;
			case CANCELLED -> NotificationType.TIME_OFF_CANCELLED;
		};
		Map<String, String> values = Map.of(Placeholder.TIME_OFF_KIND, kindLabel(event.kind()),
				Placeholder.TIME_OFF_PERIOD, period(event.startsAt(), event.endsAt(), studioZone()),
				Placeholder.NOTE, note(event.note()));
		notifications.notifyNow(List.of(new NotificationRequest(type, event.staffAccountId(), NotificationRole.TRAINER,
				null, null, type.name() + ":" + event.timeOffId() + ":" + event.staffAccountId(), values,
				Map.of("timeOffId", event.timeOffId().toString(), "startsAt", event.startsAt().toString()),
				Instant.now())));
	}

	private ZoneId studioZone() {
		return ZoneId.of(studios.findFirstBy().map(Studio::getTimeZone).orElse("Europe/Warsaw"));
	}

	static String kindLabel(String kind) {
		return switch (kind) {
			case "VACATION" -> "Urlop";
			case "SICK_LEAVE" -> "Zwolnienie lekarskie";
			default -> "Inna niedostępność";
		};
	}

	/**
	 * The period as the trainer knows it, in the studio time zone: "19.11" or "19.11–20.11" for
	 * whole days, "17.11, 17:00–19:00" within one day, otherwise "17.11 17:00 – 18.11 9:00".
	 */
	static String period(Instant startsAt, Instant endsAt, ZoneId zone) {
		ZonedDateTime start = startsAt.atZone(zone);
		ZonedDateTime end = endsAt.atZone(zone);
		if (start.toLocalTime().equals(LocalTime.MIDNIGHT) && end.toLocalTime().equals(LocalTime.MIDNIGHT)) {
			LocalDate lastDay = end.toLocalDate().minusDays(1);
			return lastDay.equals(start.toLocalDate()) ? DAY.format(start)
					: DAY.format(start) + "–" + DAY.format(lastDay);
		}
		if (start.toLocalDate().equals(end.toLocalDate())) {
			return DAY.format(start) + ", " + TIME.format(start) + "–" + TIME.format(end);
		}
		return DAY.format(start) + " " + TIME.format(start) + " – " + DAY.format(end) + " " + TIME.format(end);
	}

	private static String note(@Nullable String note) {
		return note == null || note.isBlank() ? "" : "Notatka studia: " + note.strip();
	}

}
