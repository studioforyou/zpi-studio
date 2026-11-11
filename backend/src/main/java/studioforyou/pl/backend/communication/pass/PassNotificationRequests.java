package studioforyou.pl.backend.communication.pass;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.NotificationType.Placeholder;
import studioforyou.pl.backend.configuration.Studio;
import studioforyou.pl.backend.configuration.StudioRepository;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.NotificationRecipient;

/**
 * Builds notifications about a participant's pass: an adult gets its own, a child's goes to every
 * active guardian. Tapping one opens the participant's passes.
 */
@Component
class PassNotificationRequests {

	static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	private final CustomerProfiles customerProfiles;

	private final StudioRepository studios;

	PassNotificationRequests(CustomerProfiles customerProfiles, StudioRepository studios) {
		this.customerProfiles = customerProfiles;
		this.studios = studios;
	}

	ZoneId studioZone() {
		return ZoneId.of(studios.findFirstBy().map(Studio::getTimeZone).orElse("Europe/Warsaw"));
	}

	/**
	 * @param dedupPrefix the start of the dedup key, unique for the pass and the moment, e.g.
	 * {@code PASS_LAST_ENTRY_USED:<pass>}; the recipient's account is appended
	 */
	List<NotificationRequest> forPass(NotificationType type, UUID passId, UUID customerId, String productName,
			@Nullable LocalDate validUntil, Map<String, String> extraValues, String dedupPrefix,
			Instant scheduledFor) {
		Map<String, String> values = new HashMap<>(extraValues);
		values.put(Placeholder.PASS, productName);
		values.put(Placeholder.VALID_UNTIL, validUntil == null ? "" : DATE.format(validUntil));
		List<NotificationRequest> requests = new ArrayList<>();
		for (NotificationRecipient recipient : customerProfiles.notificationRecipients(List.of(customerId))) {
			requests.add(new NotificationRequest(type, recipient.accountId(), NotificationRole.CLIENT,
					recipient.forChild() ? recipient.profileId() : null,
					recipient.guardian() ? recipient.firstName() : null, dedupPrefix + ":" + recipient.accountId(),
					values, Map.of("passId", passId.toString(), "profileId", recipient.profileId().toString()),
					scheduledFor));
		}
		return requests;
	}

}
