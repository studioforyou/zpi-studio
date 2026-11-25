package studioforyou.pl.backend.communication.announcement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AudienceRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.NotificationRecipient;
import studioforyou.pl.backend.documents.DocumentKind;
import studioforyou.pl.backend.documents.MarketingConsents;
import studioforyou.pl.backend.identity.AccountRoles;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.EventSummary;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.trainings.TrainingGroups;

/**
 * Who gets an announcement. Everybody gets it at most once; a child's announcement goes to its
 * guardians, naming the child. A marketing announcement only reaches people who accepted the
 * consent to marketing notifications in the app.
 */
@Component
class AnnouncementRecipients {

	/** How far ahead a class can be picked as the audience. */
	static final Duration EVENT_WINDOW = Duration.ofDays(30);

	private static final String CLIENT = "CLIENT";

	private static final String TRAINER = "TRAINER";

	private final Clock clock = Clock.systemUTC();

	private final AccountRoles accountRoles;

	private final CustomerProfiles customers;

	private final TrainingGroups groups;

	private final ScheduledEvents events;

	private final Participations participations;

	private final MarketingConsents consents;

	private final StudioSettings settings;

	AnnouncementRecipients(AccountRoles accountRoles, CustomerProfiles customers, TrainingGroups groups,
			ScheduledEvents events, Participations participations, MarketingConsents consents,
			StudioSettings settings) {
		this.accountRoles = accountRoles;
		this.customers = customers;
		this.groups = groups;
		this.events = events;
		this.participations = participations;
		this.consents = consents;
		this.settings = settings;
	}

	/** The recipients of the audience, and how many a marketing announcement leaves out. */
	Resolved resolve(AudienceRequest request) {
		List<Recipient> all = switch (request.audience()) {
			case PERSON -> person(require(request.accountId(), "accountId"));
			case GROUP -> participants(groupProfiles(require(request.trainingGroupId(), "trainingGroupId")));
			case EVENT -> participants(eventProfiles(require(request.eventId(), "eventId")));
			case ALL -> accountRoles.findActiveWithRole(CLIENT).stream()
				.map((account) -> new Recipient(account, NotificationRole.CLIENT, null, List.of()))
				.toList();
		};
		if (!request.isMarketing()) {
			return new Resolved(sorted(all), 0);
		}
		Map<UUID, UUID> ownProfiles = customers.ownProfileIds(all.stream().map(Recipient::accountId).toList());
		Set<UUID> consenting = consents.consenting(ownProfiles.values(), DocumentKind.MARKETING_PUSH);
		List<Recipient> reached = all.stream()
			.filter((recipient) -> consenting.contains(ownProfiles.get(recipient.accountId())))
			.toList();
		return new Resolved(sorted(reached), all.size() - reached.size());
	}

	/** The target the announcement was for, checked again when it is sent. */
	@Nullable EventSummary event(UUID eventId) {
		return events.findGroupEvents(List.of(eventId)).stream().findFirst().orElse(null);
	}

	private List<Recipient> person(UUID accountId) {
		if (accountRoles.findActive(List.of(accountId)).isEmpty()) {
			throw ApiException.invalidField("accountId", FieldErrorCode.INVALID_VALUE, "No active account with this ID.");
		}
		Set<String> roles = accountRoles.rolesOf(List.of(accountId)).getOrDefault(accountId, Set.of());
		boolean client = roles.contains(CLIENT);
		boolean trainer = roles.contains(TRAINER);
		if (!client && !trainer) {
			throw ApiException.invalidField("accountId", FieldErrorCode.INVALID_VALUE,
					"The account is neither a client nor a trainer, so it does not use the app.");
		}
		// A client who is also a trainer sees it whichever role they use the app in.
		NotificationRole role = client && trainer ? null : client ? NotificationRole.CLIENT : NotificationRole.TRAINER;
		return List.of(new Recipient(accountId, role, null, List.of()));
	}

	private Set<UUID> groupProfiles(UUID groupId) {
		if (groups.names(List.of(groupId)).isEmpty()) {
			throw ApiException.invalidField("trainingGroupId", FieldErrorCode.INVALID_VALUE, "No training group with this ID.");
		}
		Set<UUID> profiles = new HashSet<>(groups.findMembers(groupId, settings.today()));
		profiles.addAll(participations.findParticipants(events.findUpcomingEventIdsOfGroup(groupId, clock.instant())));
		return profiles;
	}

	private Set<UUID> eventProfiles(UUID eventId) {
		EventSummary event = event(eventId);
		Instant now = clock.instant();
		if (event == null || event.cancelled() || !event.endsAt().isAfter(now)
				|| event.startsAt().isAfter(now.plus(EVENT_WINDOW))) {
			throw ApiException.invalidField("eventId", FieldErrorCode.INVALID_VALUE,
					"Pick a class or workshop that is not cancelled and takes place in the next 30 days.");
		}
		return participations.findParticipants(List.of(eventId));
	}

	/**
	 * One recipient per account acting for the participants: the participant's own account, and a
	 * child's guardians, who see which children it is about unless it is also about themselves.
	 */
	private List<Recipient> participants(Collection<UUID> profileIds) {
		List<NotificationRecipient> rows = customers.notificationRecipients(profileIds);
		Set<UUID> active = accountRoles.findActive(rows.stream().map(NotificationRecipient::accountId).toList());
		Map<UUID, List<NotificationRecipient>> byAccount = new LinkedHashMap<>();
		for (NotificationRecipient row : rows) {
			if (active.contains(row.accountId())) {
				byAccount.computeIfAbsent(row.accountId(), (id) -> new ArrayList<>()).add(row);
			}
		}
		List<Recipient> recipients = new ArrayList<>();
		byAccount.forEach((account, entries) -> {
			boolean own = entries.stream().anyMatch((entry) -> !entry.forChild());
			List<NotificationRecipient> children = own ? List.of()
					: entries.stream().filter(NotificationRecipient::forChild).toList();
			recipients.add(new Recipient(account, NotificationRole.CLIENT,
					children.size() == 1 ? children.getFirst().profileId() : null,
					children.stream().map(NotificationRecipient::firstName).sorted().toList()));
		});
		return recipients;
	}

	private static List<Recipient> sorted(List<Recipient> recipients) {
		return recipients.stream().sorted((a, b) -> a.accountId().compareTo(b.accountId())).toList();
	}

	private static UUID require(@Nullable UUID id, String field) {
		if (id == null) {
			throw ApiException.invalidField(field, FieldErrorCode.REQUIRED, "Required for this audience.");
		}
		return id;
	}

	/**
	 * @param role the role the announcement concerns; {@code null} shows it in every role
	 * @param childId the child it is about when the account gets it only for one child
	 * @param childNames first names of the children it is about when the account gets it only as a guardian
	 */
	record Recipient(UUID accountId, @Nullable NotificationRole role, @Nullable UUID childId, List<String> childNames) {

		boolean guardianOnly() {
			return !childNames.isEmpty();
		}

	}

	record Resolved(List<Recipient> recipients, int skippedWithoutConsent) {
	}

}
