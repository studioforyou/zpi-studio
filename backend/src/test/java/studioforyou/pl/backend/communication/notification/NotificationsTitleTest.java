package studioforyou.pl.backend.communication.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NotificationsTitleTest {

	private static final String SUBJECT = "Przypomnienie o zajęciach";

	@Test
	void aSingleRoleAdultGetsThePlainTitle() {
		assertThat(Notifications.title(SUBJECT, request(null), false)).isEqualTo(SUBJECT);
	}

	@Test
	void anAccountWithSeveralRolesSeesTheRole() {
		assertThat(Notifications.title(SUBJECT, request(null), true)).isEqualTo("Klient: " + SUBJECT);
	}

	@Test
	void aGuardianSeesTheChildAndTheRoleWhenNeeded() {
		assertThat(Notifications.title(SUBJECT, request("Kuba"), false)).isEqualTo("Kuba: " + SUBJECT);
		assertThat(Notifications.title(SUBJECT, request("Kuba"), true)).isEqualTo("Klient · Kuba: " + SUBJECT);
	}

	@Test
	void cutsAnOverlongTitle() {
		assertThat(Notifications.title("x".repeat(300), request(null), false)).hasSize(200).endsWith("…");
	}

	private static NotificationRequest request(String childName) {
		return new NotificationRequest(NotificationType.CLASS_REMINDER, UUID.randomUUID(), NotificationRole.CLIENT,
				childName == null ? null : UUID.randomUUID(), childName, "key", Map.of(), Map.of(), Instant.now());
	}

}
