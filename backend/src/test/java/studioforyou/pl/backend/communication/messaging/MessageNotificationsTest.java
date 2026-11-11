package studioforyou.pl.backend.communication.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

	@Test
	void namesUpToTwoWritersAndCountsInPolish() {
		assertThat(MessageNotifications.senders(List.of("Ewa Lis"))).isEqualTo("Ewa Lis");
		assertThat(MessageNotifications.senders(List.of("Ewa Lis", "Jan Kos"))).isEqualTo("Ewa Lis, Jan Kos");
		assertThat(MessageNotifications.senders(List.of("A", "B", "C"))).isEqualTo("A, B i 1 inna osoba");
		assertThat(MessageNotifications.senders(List.of("A", "B", "C", "D"))).isEqualTo("A, B i 2 inne osoby");
		assertThat(MessageNotifications.senders(List.of("A", "B", "C", "D", "E", "F", "G")))
			.isEqualTo("A, B i 5 innych osób");
		assertThat(MessageNotifications.count(1)).isEqualTo("1 nowa wiadomość");
		assertThat(MessageNotifications.count(3)).isEqualTo("3 nowe wiadomości");
		assertThat(MessageNotifications.count(5)).isEqualTo("5 nowych wiadomości");
		assertThat(MessageNotifications.count(12)).isEqualTo("12 nowych wiadomości");
		assertThat(MessageNotifications.count(22)).isEqualTo("22 nowe wiadomości");
	}

}
