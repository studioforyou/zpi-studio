package studioforyou.pl.backend.communication.interest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.booking.BookingOpenings;
import studioforyou.pl.backend.booking.BookingOpenings.OpenedClass;
import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationStore;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.Studio;
import studioforyou.pl.backend.configuration.StudioRepository;
import studioforyou.pl.backend.customer.CustomerInterests;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.NotificationRecipient;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;

/**
 * Tells the participants interested in a group class that its booking opened
 * ({@code CLASS_BOOKING_OPENED}, #143). Interested are the participants who marked the class's
 * service type and those who took part in a class of that type in the last {@link #ATTENDED_WITHIN}.
 * Those who already booked the class or joined its waiting list get nothing, and neither does
 * anyone when the class has no free place left. For a child, its guardians are told. Run by the
 * notification job every few minutes.
 * <p>
 * A notification is due from the moment booking opens for {@link #GRACE}, like a class reminder:
 * a job run that misses the moment still sends it, but classes whose booking opened long ago (e.g.
 * when this feature is deployed) are not announced. Each participant is told about a class once.
 */
@Component
public class ClassBookingOpenedNotifications {

	/** How long after booking opens it is still announced; longer than the job interval. */
	static final Duration GRACE = Duration.ofMinutes(30);

	/** How recently a participant took part in a class of the type to count as interested. */
	static final Duration ATTENDED_WITHIN = Duration.ofDays(60);

	private static final Locale POLISH = Locale.forLanguageTag("pl-PL");

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d.MM", POLISH);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", POLISH);

	private final NotificationStore settings;

	private final Notifications notifications;

	private final BookingOpenings openings;

	private final Participations participations;

	private final CustomerInterests interests;

	private final CustomerProfiles customerProfiles;

	private final StudioRepository studios;

	ClassBookingOpenedNotifications(NotificationStore settings, Notifications notifications, BookingOpenings openings,
			Participations participations, CustomerInterests interests, CustomerProfiles customerProfiles,
			StudioRepository studios) {
		this.settings = settings;
		this.notifications = notifications;
		this.openings = openings;
		this.participations = participations;
		this.interests = interests;
		this.customerProfiles = customerProfiles;
		this.studios = studios;
	}

	/** Creates and sends the notifications due at {@code now}; returns how many were created. */
	public int sendDue(Instant now) {
		if (!settings.settings(NotificationType.CLASS_BOOKING_OPENED).enabled()) {
			return 0;
		}
		List<OpenedClass> opened = openings.findOpenedBetween(now.minus(GRACE), now);
		if (opened.isEmpty()) {
			return 0;
		}
		Set<UUID> serviceTypes = opened.stream()
			.map((item) -> item.event().serviceTypeId())
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, Set<UUID>> marked = interests.findInterested(serviceTypes);
		Map<UUID, Set<UUID>> attended = participations.findRecentParticipants(serviceTypes, now.minus(ATTENDED_WITHIN),
				now);

		List<Due> due = new ArrayList<>();
		for (OpenedClass item : opened) {
			UUID serviceType = item.event().serviceTypeId();
			Set<UUID> interested = new HashSet<>(marked.getOrDefault(serviceType, Set.of()));
			interested.addAll(attended.getOrDefault(serviceType, Set.of()));
			interested.removeAll(item.takingPart());
			interested.forEach((profileId) -> due.add(new Due(item, profileId)));
		}
		if (due.isEmpty()) {
			return 0;
		}
		return notifications.notifyNow(requests(due, now));
	}

	private List<NotificationRequest> requests(List<Due> due, Instant now) {
		Map<UUID, List<NotificationRecipient>> recipients = customerProfiles
			.notificationRecipients(due.stream().map(Due::profileId).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.groupingBy(NotificationRecipient::profileId));
		ZoneId zone = ZoneId.of(studios.findFirstBy().map(Studio::getTimeZone).orElse("Europe/Warsaw"));
		List<NotificationRequest> requests = new ArrayList<>();
		for (Due item : due) {
			Occurrence event = item.opened().event();
			Map<String, String> values = values(event, now, zone);
			for (NotificationRecipient recipient : recipients.getOrDefault(item.profileId(), List.of())) {
				requests.add(new NotificationRequest(NotificationType.CLASS_BOOKING_OPENED, recipient.accountId(),
						NotificationRole.CLIENT, recipient.forChild() ? recipient.profileId() : null,
						recipient.guardian() ? recipient.firstName() : null,
						"CLASS_BOOKING_OPENED:" + event.id() + ":" + item.profileId() + ":" + recipient.accountId(),
						values,
						Map.of("eventId", event.id().toString(), "profileId", recipient.profileId().toString(),
								"serviceTypeId", event.serviceTypeId().toString()),
						item.opened().opensAt()));
			}
		}
		return requests;
	}

	static Map<String, String> values(Occurrence event, Instant now, ZoneId zone) {
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
		String rooms = event.resources()
			.stream()
			.map(GroupClassSchedule.Room::name)
			.filter(Objects::nonNull)
			.collect(Collectors.joining(", "));
		return Map.of(Placeholder.CLASS, title(event), Placeholder.DAY, day, Placeholder.TIME, TIME.format(start),
				Placeholder.ROOM, rooms.isBlank() ? "studio" : rooms);
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

	/** A participant to tell about a class. */
	private record Due(OpenedClass opened, UUID profileId) {
	}

}
