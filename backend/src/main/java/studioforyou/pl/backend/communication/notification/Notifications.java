package studioforyou.pl.backend.communication.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import studioforyou.pl.backend.communication.notification.NotificationStore.TypeSettings;
import studioforyou.pl.backend.identity.AccountRoles;

/**
 * The one way to notify someone: every notification, whether from the reminder job or from a
 * listener of a business event, goes through {@link #notifyNow}. It applies the administrator's
 * settings (a switched-off type creates nothing), fills the type's template, marks who and which
 * role the notification is about, stores it once per deduplication key and sends it straight away.
 */
@Service
public class Notifications {

	/** Roles that use the mobile app; an account with more than one of them sees which one a notification concerns. */
	private static final Set<String> APP_ROLES = Set.of(NotificationRole.CLIENT.name(), NotificationRole.TRAINER.name());

	private static final int TITLE_MAX = 200;

	private final NotificationStore store;

	private final NotificationDispatcher dispatcher;

	private final AccountRoles accountRoles;

	Notifications(NotificationStore store, NotificationDispatcher dispatcher, AccountRoles accountRoles) {
		this.store = store;
		this.dispatcher = dispatcher;
		this.accountRoles = accountRoles;
	}

	/**
	 * Creates the notifications that do not exist yet and sends them. Call it after the business
	 * change is committed, never inside its transaction: a push failure must not undo it.
	 *
	 * @return how many new notifications were created
	 */
	public int notifyNow(List<NotificationRequest> requests) {
		List<UUID> created = create(requests);
		dispatcher.dispatch(created);
		return created.size();
	}

	/** Stores the notifications without sending them; returns the IDs of the new ones. */
	List<UUID> create(List<NotificationRequest> requests) {
		if (requests.isEmpty()) {
			return List.of();
		}
		Map<NotificationType, TypeSettings> settings = requests.stream()
			.map(NotificationRequest::type)
			.distinct()
			.collect(Collectors.toMap((type) -> type, store::settings));
		Map<UUID, Set<String>> roles = accountRoles
			.rolesOf(requests.stream().map(NotificationRequest::recipientId).collect(Collectors.toSet()));
		List<UUID> created = new ArrayList<>();
		for (NotificationRequest request : requests) {
			TypeSettings typeSettings = settings.get(request.type());
			if (!typeSettings.enabled()) {
				continue;
			}
			boolean severalRoles = roles.getOrDefault(request.recipientId(), Set.of()).stream()
				.filter(APP_ROLES::contains)
				.count() > 1;
			String title = title(typeSettings.subject(), request, severalRoles);
			String body = NotificationTemplates.render(typeSettings.body(), request.values()).strip();
			store.insert(request, title, body).ifPresent(created::add);
		}
		return created;
	}

	/**
	 * The rendered title with what it concerns in front: the role for an account with several
	 * roles, the participant's first name for a guardian of a child (also about the guardian),
	 * e.g. "Klient · Kuba: Przypomnienie o zajęciach".
	 */
	static String title(String subjectTemplate, NotificationRequest request, boolean severalRoles) {
		String title = NotificationTemplates.render(subjectTemplate, request.values()).strip();
		List<String> context = new ArrayList<>();
		if (severalRoles) {
			context.add(request.role().label());
		}
		if (request.subjectName() != null && !request.subjectName().isBlank()) {
			context.add(request.subjectName().strip());
		}
		if (!context.isEmpty()) {
			title = String.join(" · ", context) + ": " + title;
		}
		return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX - 1) + "…";
	}

}
