package studioforyou.pl.backend.communication.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import studioforyou.pl.backend.communication.notification.NotificationRole;

class MessageNotificationsTest {

	@Test
	void aDirectMessageConcernsTheRoleTheRecipientTalksIn() {
		assertThat(MessagingService.directRole(Set.of("CLIENT"), true)).isEqualTo(NotificationRole.CLIENT);
		assertThat(MessagingService.directRole(Set.of("TRAINER"), false)).isEqualTo(NotificationRole.TRAINER);
		// A trainer who is also a client: a trainer writes to them as to a client, a client as to a trainer.
		assertThat(MessagingService.directRole(Set.of("CLIENT", "TRAINER"), true)).isEqualTo(NotificationRole.CLIENT);
		assertThat(MessagingService.directRole(Set.of("CLIENT", "TRAINER"), false))
			.isEqualTo(NotificationRole.TRAINER);
	}

	@Test
	void thePreviewIsOneLineOfAtMostAHundredCharactersCutAtAWord() {
		assertThat(MessageNotifications.preview("  Jutro\n\no 18?  ")).isEqualTo("Jutro o 18?");
		String words = "Weźcie wodę i ręcznik, ".repeat(10);
		String preview = MessageNotifications.preview(words);
		assertThat(preview).hasSizeLessThanOrEqualTo(MessageNotifications.PREVIEW_LENGTH)
			.endsWith("ręcznik, Weźcie…")
			.startsWith("Weźcie wodę");
		assertThat(MessageNotifications.preview("a".repeat(150))).isEqualTo("a".repeat(99) + "…");
	}

}
