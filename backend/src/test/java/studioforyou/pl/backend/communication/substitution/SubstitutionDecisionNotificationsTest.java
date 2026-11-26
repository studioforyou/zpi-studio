package studioforyou.pl.backend.communication.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.scheduling.SubstitutionDecided;
import studioforyou.pl.backend.scheduling.SubstitutionDecided.Outcome;
import studioforyou.pl.backend.staff.StaffProfiles;

class SubstitutionDecisionNotificationsTest {

	private static final UUID REQUEST = UUID.randomUUID(), EVENT = UUID.randomUUID();
	private static final UUID ANNA = UUID.randomUUID(), MAREK = UUID.randomUUID();
	private static final UUID ANNA_ACCOUNT = UUID.randomUUID(), MAREK_ACCOUNT = UUID.randomUUID();

	private final Notifications notifications = mock(Notifications.class);
	private final StaffProfiles staff = mock(StaffProfiles.class);
	private final StudioSettings studio = mock(StudioSettings.class);

	@Test
	void coveredTellsTheOriginalTrainerWhoTakesTheEventAndTheSubstituteThatTheyLeadIt() {
		var requests = send(decision(Outcome.COVERED, MAREK, false, "Dzięki!"));
		assertThat(requests).extracting(NotificationRequest::type).containsExactly(NotificationType.SUBSTITUTION_COVERED,
				NotificationType.SUBSTITUTION_ASSIGNED);
		var original = requests.getFirst();
		assertThat(original.recipientId()).isEqualTo(ANNA_ACCOUNT);
		assertThat(original.role()).isEqualTo(NotificationRole.TRAINER);
		assertThat(original.values()).containsEntry("zastępca", "Marek Lis").containsEntry("godzina", "18:00–19:00")
			.containsEntry("dzień", "wt., 1.12").doesNotContainKey("notatka");
		assertThat(original.data()).containsEntry("eventId", EVENT.toString()).doesNotContainKey("candidate");
		assertThat(original.dedupKey()).isEqualTo("SUBSTITUTION_COVERED:" + REQUEST + ":2:" + ANNA_ACCOUNT);
		var substitute = requests.getLast();
		assertThat(substitute.recipientId()).isEqualTo(MAREK_ACCOUNT);
		assertThat(substitute.values()).containsEntry("notatka", "Notatka studia: Dzięki!");
		assertThat(substitute.data()).containsEntry("requestId", REQUEST.toString()).containsEntry("candidate", "true");
	}

	@Test
	void aTrainerWhoTookTheEventThemselvesGetsNoConfirmation() {
		assertThat(send(decision(Outcome.COVERED, MAREK, true, null))).extracting(NotificationRequest::type)
			.containsExactly(NotificationType.SUBSTITUTION_COVERED);
	}

	@Test
	void rejectedClaimTellsOnlyTheCandidate() {
		var requests = send(decision(Outcome.CLAIM_REJECTED, MAREK, false, null));
		assertThat(requests).singleElement().satisfies((r) -> {
			assertThat(r.type()).isEqualTo(NotificationType.SUBSTITUTION_CLAIM_REJECTED);
			assertThat(r.recipientId()).isEqualTo(MAREK_ACCOUNT);
			assertThat(r.values()).containsEntry("notatka", "");
		});
	}

	@Test
	void closingTellsTheOriginalTrainerAndAWaitingCandidate() {
		assertThat(send(decision(Outcome.CLOSED, MAREK, false, "Poprowadzę sama"))).extracting(NotificationRequest::type,
				NotificationRequest::recipientId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(NotificationType.SUBSTITUTION_CLOSED, ANNA_ACCOUNT),
					org.assertj.core.groups.Tuple.tuple(NotificationType.SUBSTITUTION_CLAIM_REJECTED, MAREK_ACCOUNT));
		assertThat(send(decision(Outcome.CLOSED, null, false, null))).extracting(NotificationRequest::type)
			.containsExactly(NotificationType.SUBSTITUTION_CLOSED);
	}

	@Test
	void aTrainerWithoutAnAccountIsSkipped() {
		when(this.staff.findAccountIds(anyCollection())).thenReturn(Map.of());
		when(this.studio.zone()).thenReturn(ZoneId.of("Europe/Warsaw"));
		listener().decided(decision(Outcome.CLAIM_REJECTED, MAREK, false, null));
		verifyNoInteractions(this.notifications);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private List<NotificationRequest> send(SubstitutionDecided event) {
		reset(this.notifications);
		when(this.staff.findAccountIds(anyCollection())).thenReturn(Map.of(ANNA, ANNA_ACCOUNT, MAREK, MAREK_ACCOUNT));
		when(this.studio.zone()).thenReturn(ZoneId.of("Europe/Warsaw"));
		listener().decided(event);
		ArgumentCaptor<List<NotificationRequest>> captured = ArgumentCaptor.forClass((Class) List.class);
		verify(this.notifications).notifyNow(captured.capture());
		return captured.getValue();
	}

	private SubstitutionDecisionNotifications listener() {
		return new SubstitutionDecisionNotifications(this.notifications, this.staff, this.studio);
	}

	private static SubstitutionDecided decision(Outcome outcome, @Nullable UUID substitute, boolean selfClaimed,
			@Nullable String note) {
		return new SubstitutionDecided(REQUEST, 2, EVENT, outcome, ANNA, substitute, substitute == null ? null : "Marek Lis",
				selfClaimed, "Pole dance", "Sala 1", Instant.parse("2026-12-01T17:00:00Z"), Instant.parse("2026-12-01T18:00:00Z"), note);
	}

}
