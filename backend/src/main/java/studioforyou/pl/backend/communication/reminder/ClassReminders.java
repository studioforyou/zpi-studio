package studioforyou.pl.backend.communication.reminder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.Participations.ActiveReservation;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationStore;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.Studio;
import studioforyou.pl.backend.configuration.StudioRepository;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.NotificationRecipient;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.ScheduledEvents;

/**
 * Reminders about upcoming classes and trainings ({@code CLASS_REMINDER}). For every reminder
 * time the administrator set (e.g. 24 h and 2 h before), each participant with an active
 * reservation gets one reminder; for a child, each of its guardians. Run by the notification job
 * every few minutes.
 * <p>
 * A reminder is due from {@code start - lead time} for {@link #GRACE}: a job run that misses the
 * moment still sends it, but a long outage does not send a stale one. A reservation made after
 * that moment gets no reminder for that lead time (booking 3 hours before a class gives the 2 h
 * reminder only). Reservations cancelled and classes cancelled before the moment get nothing.
 */
@Component
public class ClassReminders {

	/** How long after the moment a reminder is still sent; longer than the job interval. */
	static final Duration GRACE = Duration.ofMinutes(30);

	private static final Locale POLISH = Locale.forLanguageTag("pl-PL");

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d.MM", POLISH);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", POLISH);

	private final NotificationStore settings;

	private final Notifications notifications;

	private final ScheduledEvents scheduledEvents;

	private final GroupClassSchedule schedule;

	private final Participations participations;

	private final CustomerProfiles customerProfiles;

	private final StudioRepository studios;

	ClassReminders(NotificationStore settings, Notifications notifications, ScheduledEvents scheduledEvents,
			GroupClassSchedule schedule, Participations participations, CustomerProfiles customerProfiles,
			StudioRepository studios) {
		this.settings = settings;
		this.notifications = notifications;
		this.scheduledEvents = scheduledEvents;
		this.schedule = schedule;
		this.participations = participations;
		this.customerProfiles = customerProfiles;
		this.studios = studios;
	}

	/** Creates and sends the reminders due at {@code now}; returns how many were created. */
	public int sendDue(Instant now) {
		NotificationStore.TypeSettings type = settings.settings(NotificationType.CLASS_REMINDER);
		if (!type.enabled() || type.leadTimes().isEmpty()) {
			return 0;
		}
		Duration longest = Duration.ofMinutes(type.leadTimes().getFirst());
		List<UUID> eventIds = scheduledEvents.findIdsStartingBetween(now, now.plus(longest));
		if (eventIds.isEmpty()) {
			return 0;
		}
		Map<UUID, Occurrence> events = schedule.findStartingBetween(eventIds, now, now.plus(longest)).stream()
			.filter((event) -> !"CANCELLED".equals(event.status()))
			.collect(Collectors.toMap(Occurrence::id, Function.identity()));

		List<Due> due = new ArrayList<>();
		for (ActiveReservation reservation : participations.findActiveReservations(events.keySet())) {
			Occurrence event = events.get(reservation.eventId());
			for (int minutes : type.leadTimes()) {
				if (isDue(event.startsAt(), minutes, reservation.createdAt(), now)) {
					due.add(new Due(reservation, event, minutes));
				}
			}
		}
		if (due.isEmpty()) {
			return 0;
		}
		return notifications.notifyNow(requests(due, now));
	}

	/** Whether the reminder {@code minutes} before {@code startsAt} is due at {@code now}. */
	static boolean isDue(Instant startsAt, int minutes, Instant reservedAt, Instant now) {
		Instant moment = startsAt.minus(Duration.ofMinutes(minutes));
		return !now.isBefore(moment) && now.isBefore(moment.plus(GRACE)) && now.isBefore(startsAt)
				&& !reservedAt.isAfter(moment);
	}

	private List<NotificationRequest> requests(Collection<Due> due, Instant now) {
		Map<UUID, List<NotificationRecipient>> recipients = customerProfiles
			.notificationRecipients(due.stream().map((d) -> d.reservation().customerId()).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.groupingBy(NotificationRecipient::profileId));
		ZoneId zone = ZoneId.of(studios.findFirstBy().map(Studio::getTimeZone).orElse("Europe/Warsaw"));
		List<NotificationRequest> requests = new ArrayList<>();
		for (Due item : due) {
			Map<String, String> values = values(item.event(), item.minutes(), now, zone);
			for (NotificationRecipient recipient : recipients.getOrDefault(item.reservation().customerId(), List.of())) {
				requests.add(new NotificationRequest(NotificationType.CLASS_REMINDER, recipient.accountId(),
						NotificationRole.CLIENT, recipient.forChild() ? recipient.profileId() : null,
						recipient.forChild() ? recipient.firstName() : null,
						"CLASS_REMINDER:" + item.reservation().id() + ":" + item.minutes() + ":" + recipient.accountId(),
						values,
						Map.of("eventId", item.event().id().toString(), "reservationId",
								item.reservation().id().toString(), "profileId", recipient.profileId().toString()),
						item.event().startsAt().minus(Duration.ofMinutes(item.minutes()))));
			}
		}
		return requests;
	}

	static Map<String, String> values(Occurrence event, int minutes, Instant now, ZoneId zone) {
		ZonedDateTime start = event.startsAt().atZone(zone);
		LocalDate today = now.atZone(zone).toLocalDate();
		String day;
		if (start.toLocalDate().equals(today)) {
			day = "dziś";
		}
		else if (start.toLocalDate().equals(today.plusDays(1))) {
			day = "jutro";
		}
		else {
			day = DATE.format(start);
		}
		String rooms = event.resources().stream()
			.map(GroupClassSchedule.Room::name)
			.filter(Objects::nonNull)
			.collect(Collectors.joining(", "));
		return Map.of(Placeholder.CLASS, title(event), Placeholder.DAY, day, Placeholder.TIME, TIME.format(start),
				Placeholder.ROOM, rooms.isBlank() ? "studio" : rooms, Placeholder.LEAD_TIME, leadTime(minutes));
	}

	private static String title(Occurrence event) {
		if (event.title() != null && !event.title().isBlank()) {
			return event.title().strip();
		}
		if (event.serviceTypeName() != null && !event.serviceTypeName().isBlank()) {
			return event.serviceTypeName().strip();
		}
		return "Zajęcia";
	}

	/** E.g. "2 godz.", "1 godz. 30 min", "45 min", "2 dni". */
	static String leadTime(int minutes) {
		if (minutes % 1440 == 0) {
			int days = minutes / 1440;
			return days == 1 ? "1 dzień" : days + " dni";
		}
		int hours = minutes / 60;
		int rest = minutes % 60;
		if (hours == 0) {
			return rest + " min";
		}
		return rest == 0 ? hours + " godz." : hours + " godz. " + rest + " min";
	}

	private record Due(ActiveReservation reservation, Occurrence event, int minutes) {
	}

}
