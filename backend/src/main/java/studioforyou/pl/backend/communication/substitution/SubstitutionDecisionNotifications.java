package studioforyou.pl.backend.communication.substitution;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.scheduling.SubstitutionDecided;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the trainers a substitution decision concerns, right after it commits: the trainer who
 * asked to be replaced learns whether someone takes the event, the claiming trainer whether the
 * studio accepted the claim. A trainer who took the event themselves gets no confirmation of
 * their own action.
 */
@Component
class SubstitutionDecisionNotifications {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, d.MM", Locale.forLanguageTag("pl"));

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private final Notifications notifications;
	private final StaffProfiles staff;
	private final StudioSettings studio;

	SubstitutionDecisionNotifications(Notifications notifications, StaffProfiles staff, StudioSettings studio) {
		this.notifications = notifications; this.staff = staff; this.studio = studio;
	}

	@Async
	@TransactionalEventListener
	void decided(SubstitutionDecided event) {
		List<Notice> notices = notices(event);
		Map<UUID, UUID> accounts = this.staff.findAccountIds(notices.stream().map(Notice::staffId).toList());
		Map<String, String> base = values(event);
		List<NotificationRequest> requests = new ArrayList<>();
		for (Notice notice : notices) {
			UUID account = accounts.get(notice.staffId());
			if (account == null) continue;
			Map<String, String> values = new HashMap<>(base);
			if (notice.type() == NotificationType.SUBSTITUTION_COVERED) {
				values.put(Placeholder.SUBSTITUTE, event.substituteName() == null ? "Inny trener" : event.substituteName());
			}
			else {
				values.put(Placeholder.NOTE, note(event.note()));
			}
			requests.add(new NotificationRequest(notice.type(), account, NotificationRole.TRAINER, null, null,
					notice.type().name() + ":" + event.requestId() + ":" + event.version() + ":" + account,
					values, data(event, notice), Instant.now()));
		}
		if (!requests.isEmpty()) this.notifications.notifyNow(requests);
	}

	/** Who hears what. */
	static List<Notice> notices(SubstitutionDecided event) {
		UUID substitute = event.substituteStaffId();
		return switch (event.outcome()) {
			case COVERED -> Stream.of(new Notice(NotificationType.SUBSTITUTION_COVERED, event.originalStaffId(), false),
					substitute == null || event.selfClaimed() ? null
							: new Notice(NotificationType.SUBSTITUTION_ASSIGNED, substitute, true))
				.filter(java.util.Objects::nonNull).toList();
			case CLAIM_REJECTED -> substitute == null ? List.of()
					: List.of(new Notice(NotificationType.SUBSTITUTION_CLAIM_REJECTED, substitute, true));
			case CLOSED -> Stream.of(new Notice(NotificationType.SUBSTITUTION_CLOSED, event.originalStaffId(), false),
					substitute == null ? null : new Notice(NotificationType.SUBSTITUTION_CLAIM_REJECTED, substitute, true))
				.filter(java.util.Objects::nonNull).toList();
		};
	}

	private Map<String, String> values(SubstitutionDecided event) {
		var start = event.startsAt().atZone(this.studio.zone());
		var end = event.endsAt().atZone(this.studio.zone());
		return Map.of(Placeholder.CLASS, event.title(), Placeholder.ROOM, event.rooms(), Placeholder.DAY, DAY.format(start),
				Placeholder.TIME, TIME.format(start) + "–" + TIME.format(end));
	}

	/** The app opens the substitution for the candidate and the event in the schedule for the original trainer. */
	private static Map<String, String> data(SubstitutionDecided event, Notice notice) {
		Map<String, String> data = new HashMap<>(Map.of("requestId", event.requestId().toString(),
				"eventId", event.eventId().toString(), "title", event.title(), "rooms", event.rooms(),
				"startsAt", event.startsAt().toString(), "endsAt", event.endsAt().toString(), "role", "TRAINER"));
		if (notice.candidate()) data.put("candidate", "true");
		return data;
	}

	private static String note(@Nullable String note) {
		return note == null || note.isBlank() ? "" : "Notatka studia: " + note.strip();
	}

	/**
	 * @param candidate whether the trainer is the substitute rather than the one who asked to be replaced
	 */
	record Notice(NotificationType type, UUID staffId, boolean candidate) {
	}

}
