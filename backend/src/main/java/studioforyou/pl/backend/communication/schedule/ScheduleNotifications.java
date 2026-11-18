package studioforyou.pl.backend.communication.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.NotificationRecipient;
import studioforyou.pl.backend.scheduling.ScheduleChangesRecorded;
import studioforyou.pl.backend.scheduling.ScheduleChangesRecorded.Notice;
import studioforyou.pl.backend.scheduling.ScheduleEventCancelled;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * Tells participants and staff about changes of their classes and trainings, right after each
 * change commits:
 * <ul>
 * <li>{@code CLASS_CHANGED}: the time, the rooms or the staff changed. Each change notice the
 * schedule leaves (#120) becomes one notification: participants with a reservation or on the
 * waiting list, and the staff before and after the change.</li>
 * <li>{@code CLASS_CANCELLED}: the class or training was cancelled, to its participants and, unless
 * it was cancelled for too few participants, to its staff.</li>
 * <li>{@code CLASS_AUTO_CANCELLED}: the class was cancelled for too few participants, to its
 * staff.</li>
 * </ul>
 * A child's notifications go to its guardians. Tapping a notification opens the class.
 */
@Component
class ScheduleNotifications {

	/** The data key with the event the notification is about. */
	static final String EVENT_ID = "eventId";

	private static final Locale POLISH = Locale.forLanguageTag("pl-PL");

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d.MM", POLISH);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", POLISH);

	private final Notifications notifications;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	private final StudioSettings studio;

	ScheduleNotifications(Notifications notifications, CustomerProfiles customers, StaffProfiles staff,
			StudioSettings studio) {
		this.notifications = notifications;
		this.customers = customers;
		this.staff = staff;
		this.studio = studio;
	}

	@Async
	@TransactionalEventListener
	void on(ScheduleChangesRecorded event) {
		Instant now = Instant.now();
		ZoneId zone = this.studio.zone();
		List<Notice> notices = event.notices();
		Map<UUID, List<NotificationRecipient>> recipients = recipients(notices.stream()
			.map(Notice::customerId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet()));
		Map<UUID, UUID> staffAccounts = this.staff.findAccountIds(notices.stream()
			.map(Notice::staffId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet()));
		List<NotificationRequest> requests = new ArrayList<>();
		for (Notice notice : notices) {
			Map<String, String> values = changeValues(notice, now, zone);
			String key = NotificationType.CLASS_CHANGED.name() + ":" + notice.noticeId() + ":";
			if (notice.customerId() != null) {
				for (NotificationRecipient recipient : recipients.getOrDefault(notice.customerId(), List.of())) {
					requests.add(forParticipant(NotificationType.CLASS_CHANGED, recipient, key + recipient.accountId(),
							values, notice.eventId(), now));
				}
			}
			UUID account = (notice.staffId() != null) ? staffAccounts.get(notice.staffId()) : null;
			if (account != null) {
				requests.add(forStaff(NotificationType.CLASS_CHANGED, account, key + account, values, notice.eventId(),
						now));
			}
		}
		this.notifications.notifyNow(requests);
	}

	@Async
	@TransactionalEventListener
	void on(ScheduleEventCancelled event) {
		Instant now = Instant.now();
		Map<String, String> values = new HashMap<>(eventValues(event.eventName(), event.startsAt(), event.rooms(), now,
				this.studio.zone()));
		values.put(Placeholder.REASON, reason(event));
		List<NotificationRequest> requests = new ArrayList<>();
		String key = ":" + event.eventId() + ":";
		for (List<NotificationRecipient> participant : recipients(event.customerIds()).values()) {
			for (NotificationRecipient recipient : participant) {
				requests.add(forParticipant(NotificationType.CLASS_CANCELLED, recipient,
						NotificationType.CLASS_CANCELLED.name() + key + recipient.profileId() + ":" + recipient.accountId(),
						values, event.eventId(), now));
			}
		}
		NotificationType staffType = NotificationType.CLASS_CANCELLED;
		if (event.reason() == ScheduleEventCancelled.Reason.TOO_FEW_PARTICIPANTS) {
			staffType = NotificationType.CLASS_AUTO_CANCELLED;
			values.put(Placeholder.BOOKED, String.valueOf(Objects.requireNonNullElse(event.participants(), 0)));
			values.put(Placeholder.MINIMUM, String.valueOf(Objects.requireNonNullElse(event.minimumParticipants(), 0)));
		}
		for (Map.Entry<UUID, UUID> member : this.staff.findAccountIds(event.staffIds()).entrySet()) {
			UUID account = member.getValue();
			requests.add(forStaff(staffType, account, staffType.name() + key + member.getKey() + ":" + account, values,
					event.eventId(), now));
		}
		this.notifications.notifyNow(requests);
	}

	private Map<UUID, List<NotificationRecipient>> recipients(Set<UUID> customerIds) {
		return this.customers.notificationRecipients(customerIds)
			.stream()
			.collect(Collectors.groupingBy(NotificationRecipient::profileId));
	}

	private static NotificationRequest forParticipant(NotificationType type, NotificationRecipient recipient,
			String dedupKey, Map<String, String> values, UUID eventId, Instant now) {
		return new NotificationRequest(type, recipient.accountId(), NotificationRole.CLIENT,
				recipient.forChild() ? recipient.profileId() : null, recipient.guardian() ? recipient.firstName() : null,
				dedupKey, values,
				Map.of(EVENT_ID, eventId.toString(), "profileId", recipient.profileId().toString(), "role",
						NotificationRole.CLIENT.name()),
				now);
	}

	private static NotificationRequest forStaff(NotificationType type, UUID account, String dedupKey,
			Map<String, String> values, UUID eventId, Instant now) {
		return new NotificationRequest(type, account, NotificationRole.TRAINER, null, null, dedupKey, values,
				Map.of(EVENT_ID, eventId.toString(), "role", NotificationRole.TRAINER.name()), now);
	}

	/** The values of a change notice: the event after the change, and what changed. */
	static Map<String, String> changeValues(Notice notice, Instant now, ZoneId zone) {
		Map<String, String> values = new HashMap<>(eventValues(notice.eventName(), notice.startsAt(), notice.rooms(),
				now, zone));
		values.put(Placeholder.LEADS, notice.leads().isEmpty() ? "brak" : String.join(", ", notice.leads()));
		values.put(Placeholder.CHANGES, changes(notice, zone));
		return values;
	}

	/** Name, day, time and rooms of an event. */
	static Map<String, String> eventValues(@Nullable String name, Instant startsAt, List<String> rooms, Instant now,
			ZoneId zone) {
		ZonedDateTime start = startsAt.atZone(zone);
		LocalDate today = now.atZone(zone).toLocalDate();
		String day = start.toLocalDate().equals(today) ? "dziś"
				: start.toLocalDate().equals(today.plusDays(1)) ? "jutro" : DATE.format(start);
		return Map.of(Placeholder.CLASS, (name != null && !name.isBlank()) ? name.strip() : "Zajęcia", Placeholder.DAY,
				day, Placeholder.TIME, TIME.format(start), Placeholder.ROOM, rooms(rooms));
	}

	/**
	 * What a change means for the person, e.g. "godzina 18:00–19:00 → 19:00–20:00, sala Sala 1 →
	 * Sala 2" or "dodano Cię do tych zajęć".
	 */
	static String changes(Notice notice, ZoneId zone) {
		switch (notice.kind()) {
			case ASSIGNED:
				return "dodano Cię do tych zajęć";
			case UNASSIGNED:
				return "usunięto Cię z tych zajęć";
			default:
				break;
		}
		List<String> parts = new ArrayList<>();
		if (!notice.previousStartsAt().equals(notice.startsAt()) || !notice.previousEndsAt().equals(notice.endsAt())) {
			ZonedDateTime previousStart = notice.previousStartsAt().atZone(zone);
			ZonedDateTime start = notice.startsAt().atZone(zone);
			if (previousStart.toLocalDate().equals(start.toLocalDate())) {
				parts.add("godzina " + hours(notice.previousStartsAt(), notice.previousEndsAt(), zone) + " → "
						+ hours(notice.startsAt(), notice.endsAt(), zone));
			}
			else {
				parts.add("termin " + DATE.format(previousStart) + " " + hours(notice.previousStartsAt(),
						notice.previousEndsAt(), zone) + " → " + DATE.format(start) + " "
						+ hours(notice.startsAt(), notice.endsAt(), zone));
			}
		}
		if (!notice.previousRooms().equals(notice.rooms())) {
			parts.add("sala " + rooms(notice.previousRooms()) + " → " + rooms(notice.rooms()));
		}
		if (!notice.previousLeads().equals(notice.leads())) {
			parts.add("prowadzący " + leads(notice.previousLeads()) + " → " + leads(notice.leads()));
		}
		// Only the assisting staff changed.
		return parts.isEmpty() ? "zmiana obsady zajęć" : String.join(", ", parts);
	}

	/** Why the event was cancelled, as the notification says it. */
	private String reason(ScheduleEventCancelled event) {
		return switch (event.reason()) {
			case TOO_FEW_PARTICIPANTS -> "zbyt mało zapisanych osób";
			case CANCELLED_BY_CLIENT -> {
				String name = (event.cancelledBy() != null)
						? this.customers.displayNamesByAccount(Set.of(event.cancelledBy())).get(event.cancelledBy()) : null;
				yield (name != null) ? "rezygnacja klienta (" + name + ")" : "rezygnacja klienta";
			}
			case CANCELLED_BY_STUDIO -> "decyzja studia";
		};
	}

	private static String hours(Instant startsAt, Instant endsAt, ZoneId zone) {
		return TIME.format(startsAt.atZone(zone)) + "–" + TIME.format(endsAt.atZone(zone));
	}

	private static String rooms(List<String> rooms) {
		return rooms.isEmpty() ? "studio" : String.join(", ", rooms);
	}

	private static String leads(List<String> leads) {
		return leads.isEmpty() ? "brak" : String.join(", ", leads);
	}

}
