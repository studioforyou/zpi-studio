package studioforyou.pl.backend.communication.announcement;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AnnouncementDetails;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AnnouncementPreview;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AnnouncementRecipient;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AnnouncementSummary;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AudienceRequest;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.RecipientStatus;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.SendAnnouncementRequest;
import studioforyou.pl.backend.communication.announcement.AnnouncementRecipients.Recipient;
import studioforyou.pl.backend.communication.announcement.AnnouncementRecipients.Resolved;
import studioforyou.pl.backend.communication.announcement.AnnouncementStore.NewAnnouncement;
import studioforyou.pl.backend.communication.announcement.AnnouncementStore.Row;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.identity.AccountRoles;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.EventSummary;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.trainings.TrainingGroups;

/**
 * The administrator's announcements: who one would reach, sending it (one in-app notification per
 * recipient, pushed after the commit) and the history with delivery figures. A sent announcement
 * cannot be changed or withdrawn.
 */
@Service
class AnnouncementService {

	private static final Set<String> APP_ROLES = Set.of("CLIENT", "TRAINER");

	private final Clock clock = Clock.systemUTC();

	private final AnnouncementRecipients recipients;

	private final AnnouncementStore store;

	private final AccountRoles accountRoles;

	private final CustomerProfiles customers;

	private final StaffProfiles staff;

	private final TrainingGroups groups;

	private final ScheduledEvents events;

	private final ApplicationEventPublisher publisher;

	AnnouncementService(AnnouncementRecipients recipients, AnnouncementStore store, AccountRoles accountRoles,
			CustomerProfiles customers, StaffProfiles staff, TrainingGroups groups, ScheduledEvents events,
			ApplicationEventPublisher publisher) {
		this.recipients = recipients;
		this.store = store;
		this.accountRoles = accountRoles;
		this.customers = customers;
		this.staff = staff;
		this.groups = groups;
		this.events = events;
		this.publisher = publisher;
	}

	AnnouncementPreview preview(AudienceRequest request) {
		Resolved resolved = recipients.resolve(request);
		int guardians = (int) resolved.recipients().stream().filter(Recipient::guardianOnly).count();
		return new AnnouncementPreview(resolved.recipients().size(), guardians, resolved.skippedWithoutConsent());
	}

	@Transactional
	AnnouncementSummary send(SendAnnouncementRequest request, UUID authorId) {
		AudienceRequest audience = request.audienceRequest();
		Resolved resolved = recipients.resolve(audience);
		if (resolved.recipients().isEmpty()) {
			throw new ApiException(ErrorCode.ANNOUNCEMENT_NO_RECIPIENTS,
					"Nobody would get this announcement" + (request.isMarketing() ? " with the marketing consent." : "."));
		}
		String title = request.title().strip();
		String body = request.body().strip();
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		UUID id = store.insert(new NewAnnouncement(authorId, request.audience(), only(audience, AnnouncementAudience.PERSON,
				request.accountId()), only(audience, AnnouncementAudience.GROUP, request.trainingGroupId()),
				only(audience, AnnouncementAudience.EVENT, request.eventId()), request.isMarketing(), title, body, now,
				resolved.skippedWithoutConsent()));
		Map<String, String> data = new HashMap<>();
		data.put("announcementId", id.toString());
		data.put("marketing", Boolean.toString(request.isMarketing()));
		String author = accountNames(List.of(authorId)).get(authorId);
		if (author != null) {
			data.put("author", author);
		}
		String audienceLabel = audienceLabel(request.audience(), request.trainingGroupId(), request.eventId());
		if (audienceLabel != null) {
			data.put("audience", audienceLabel);
		}
		Map<UUID, Set<String>> roles = accountRoles
			.rolesOf(resolved.recipients().stream().map(Recipient::accountId).toList());
		List<UUID> notifications = new ArrayList<>();
		for (Recipient recipient : resolved.recipients()) {
			Map<String, String> recipientData = new HashMap<>(data);
			String about = String.join(", ", recipient.childNames());
			if (!about.isEmpty()) {
				recipientData.put("about", about);
			}
			boolean severalRoles = roles.getOrDefault(recipient.accountId(), Set.of()).stream()
				.filter(APP_ROLES::contains).count() > 1;
			notifications.add(store.insertNotification(id, request.isMarketing(), recipient.accountId(), recipient.role(),
					recipient.childId(), title(title, recipient, severalRoles), body, recipientData, now));
		}
		publisher.publishEvent(new AnnouncementSent(id, notifications));
		return summaries(List.of(store.find(id).orElseThrow())).getFirst();
	}

	PageResponse<AnnouncementSummary> history(int page, int size) {
		PageResponse<Row> rows = store.page(page, size);
		return PageResponse.of(summaries(rows.items()), rows.page(), rows.size(), rows.totalItems());
	}

	AnnouncementDetails details(UUID id) {
		Row row = store.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Announcement not found."));
		List<AnnouncementStore.RecipientRow> rows = store.recipients(id);
		Map<UUID, String> names = accountNames(rows.stream().map(AnnouncementStore.RecipientRow::recipientId).toList());
		List<AnnouncementRecipient> list = rows.stream()
			.map((recipient) -> new AnnouncementRecipient(recipient.recipientId(),
					names.getOrDefault(recipient.recipientId(), "—"), recipient.about(),
					recipient.read() ? RecipientStatus.READ
							: recipient.pushed() ? RecipientStatus.DELIVERED : RecipientStatus.APP_ONLY))
			.sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
			.toList();
		return new AnnouncementDetails(summaries(List.of(row)).getFirst(), list);
	}

	/**
	 * The title with what it concerns in front, like other notifications: the role for an account
	 * with several roles, the children for a guardian, e.g. "Zosia: Zmiana sali".
	 */
	static String title(String title, Recipient recipient, boolean severalRoles) {
		List<String> context = new ArrayList<>();
		if (severalRoles && recipient.role() != null) {
			context.add(recipient.role().label());
		}
		if (recipient.guardianOnly()) {
			context.add(String.join(", ", recipient.childNames()));
		}
		String full = context.isEmpty() ? title : String.join(" · ", context) + ": " + title;
		return full.length() <= 200 ? full : full.substring(0, 199) + "…";
	}

	private List<AnnouncementSummary> summaries(List<Row> rows) {
		Set<UUID> accounts = new HashSet<>();
		Set<UUID> groupIds = new HashSet<>();
		Set<UUID> eventIds = new HashSet<>();
		for (Row row : rows) {
			accounts.add(row.authorId());
			if (row.targetAccountId() != null) {
				accounts.add(row.targetAccountId());
			}
			if (row.trainingGroupId() != null) {
				groupIds.add(row.trainingGroupId());
			}
			if (row.eventId() != null) {
				eventIds.add(row.eventId());
			}
		}
		Map<UUID, String> names = accountNames(accounts);
		Map<UUID, String> groupNames = groups.names(groupIds);
		Map<UUID, EventSummary> eventsById = events.findGroupEvents(eventIds).stream()
			.collect(Collectors.toMap(EventSummary::id, (event) -> event));
		return rows.stream().map((row) -> {
			AnnouncementAudience audience = AnnouncementAudience.valueOf(row.audience());
			EventSummary event = row.eventId() == null ? null : eventsById.get(row.eventId());
			String target = switch (audience) {
				case PERSON -> row.targetAccountId() == null ? null : names.get(row.targetAccountId());
				case GROUP -> row.trainingGroupId() == null ? null : groupNames.get(row.trainingGroupId());
				case EVENT -> event == null ? null : event.title();
				case ALL -> null;
			};
			return new AnnouncementSummary(row.id(), row.title(), row.body(), audience, target,
					event == null ? null : event.startsAt(), row.marketing(), row.sentAt().toInstant(),
					names.get(row.authorId()), row.recipients(), row.read(), row.pushDelivered(),
					row.skippedWithoutConsent());
		}).toList();
	}

	/** What the app shows under the title of a group or class announcement, e.g. "grupa Pilates". */
	private @Nullable String audienceLabel(AnnouncementAudience audience, @Nullable UUID groupId, @Nullable UUID eventId) {
		return switch (audience) {
			case GROUP -> groupId == null ? null : groups.names(List.of(groupId)).values().stream().findFirst()
				.map((name) -> "grupa " + name).orElse(null);
			case EVENT -> {
				EventSummary event = eventId == null ? null : recipients.event(eventId);
				yield event == null ? null : "zajęcia " + event.title();
			}
			case PERSON, ALL -> null;
		};
	}

	/** Names of people with accounts: their client profile, or their staff profile. */
	private Map<UUID, String> accountNames(Collection<UUID> accountIds) {
		Map<UUID, String> names = new LinkedHashMap<>(staff.displayNamesByAccount(accountIds));
		names.putAll(customers.displayNamesByAccount(accountIds));
		return names;
	}

	private static @Nullable UUID only(AudienceRequest request, AnnouncementAudience audience, @Nullable UUID id) {
		return request.audience() == audience ? id : null;
	}

}
