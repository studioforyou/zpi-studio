package studioforyou.pl.backend.communication.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.scheduling.SubstitutionOffers;
import studioforyou.pl.backend.scheduling.SubstitutionOpened;

class SubstitutionNotificationsTest {

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void sendsOnlyTheCheckedRecipientsWithAStableKeyAndNoPrivateReason() {
		var offers=mock(SubstitutionOffers.class); var notifications=mock(Notifications.class); var studio=mock(StudioSettings.class);
		when(studio.zone()).thenReturn(ZoneId.of("Europe/Warsaw"));
		UUID id=UUID.randomUUID(), recipient=UUID.randomUUID();
		when(offers.notification(id,0)).thenReturn(Optional.of(new SubstitutionOffers.Notice(id,0,"Trening personalny","Sala 1",
				Instant.parse("2026-11-20T09:00:00Z"),Instant.parse("2026-11-20T10:00:00Z"),List.of(recipient))));
		new SubstitutionNotifications(offers,notifications,studio).opened(new SubstitutionOpened(id,0));
		ArgumentCaptor<List<NotificationRequest>> captured=ArgumentCaptor.forClass((Class)List.class);
		verify(notifications).notifyNow(captured.capture());
		var request=captured.getValue().getFirst();
		assertThat(request.recipientId()).isEqualTo(recipient);
		assertThat(request.role()).isEqualTo(NotificationRole.TRAINER);
		assertThat(request.data()).containsEntry("requestId",id.toString()).doesNotContainKeys("reason","customerId");
		assertThat(request.dedupKey()).isEqualTo("SUBSTITUTION_AVAILABLE:"+id+":0:"+recipient);
		assertThat(request.values()).containsEntry("godzina","10:00–11:00");
	}

	@Test
	void doesNotNotifyForAnOfferThatChangedBeforeTheListenerRan() {
		var offers=mock(SubstitutionOffers.class); var notifications=mock(Notifications.class);
		UUID id=UUID.randomUUID(); when(offers.notification(id,0)).thenReturn(Optional.empty());
		new SubstitutionNotifications(offers,notifications,mock(StudioSettings.class)).opened(new SubstitutionOpened(id,0));
		verifyNoInteractions(notifications);
	}
}
