package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import studioforyou.pl.backend.communication.notification.NotificationRequest;
import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.communication.notification.Notifications;
import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;

/**
 * The skeleton for notifications about business events: a listener runs right after the
 * business transaction commits, its event is kept in the Spring Modulith registry until it
 * succeeds, and a resubmitted event does not notify twice. Not transactional: the listener only
 * runs after a real commit, so the test cleans up after itself.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, NotificationEventTests.ListenerConfiguration.class })
class NotificationEventTests {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private IncompleteEventPublications incompleteEvents;

	@Autowired
	private TestListener listener;

	@MockitoBean
	private PushSender pushSender;

	private UUID account;

	@BeforeEach
	void setUp() {
		given(pushSender.send(any())).willReturn(PushResult.sent("msg"));
		account = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", "events-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO device_token (account_id, token, platform) VALUES (:id, :token, 'ANDROID')")
			.param("id", account).param("token", "events-" + account).update();
		listener.failNextTimes(0);
	}

	@AfterEach
	void cleanUp() {
		jdbc.sql("DELETE FROM notification_delivery WHERE notification_id IN "
				+ "(SELECT id FROM notification WHERE recipient_id = :id)").param("id", account).update();
		jdbc.sql("DELETE FROM notification WHERE recipient_id = :id").param("id", account).update();
		jdbc.sql("DELETE FROM device_token WHERE account_id = :id").param("id", account).update();
		jdbc.sql("DELETE FROM event_publication WHERE serialized_event LIKE :id").param("id", "%" + account + "%")
			.update();
		jdbc.sql("DELETE FROM account WHERE id = :id").param("id", account).update();
	}

	@Test
	void notifiesRightAfterTheCommit() {
		transactions.executeWithoutResult((status) -> events.publishEvent(new SomethingHappened(account, "a")));

		await(() -> notifications() == 1);
		await(() -> incomplete() == 0);
		verify(pushSender, times(1)).send(any());
	}

	@Test
	void sendsNothingWhenTheBusinessTransactionRollsBack() throws InterruptedException {
		transactions.executeWithoutResult((status) -> {
			events.publishEvent(new SomethingHappened(account, "b"));
			status.setRollbackOnly();
		});
		Thread.sleep(300);
		assertThat(notifications()).isZero();
		assertThat(incomplete()).isZero();
	}

	@Test
	void aFailedListenerIsResubmittedWithoutNotifyingTwice() {
		listener.failNextTimes(1);
		transactions.executeWithoutResult((status) -> events.publishEvent(new SomethingHappened(account, "c")));
		await(() -> incomplete() == 1);
		assertThat(notifications()).isZero();

		// The notification job resubmits publications older than a few minutes; here: all of them.
		incompleteEvents.resubmitIncompletePublications((publication) -> true);
		await(() -> notifications() == 1 && incomplete() == 0);

		// Delivering the same event again (e.g. after a crash) creates no second notification.
		transactions.executeWithoutResult((status) -> events.publishEvent(new SomethingHappened(account, "c")));
		await(() -> incomplete() == 0);
		assertThat(notifications()).isEqualTo(1);
		verify(pushSender, times(1)).send(any());
	}

	private long notifications() {
		return jdbc.sql("SELECT count(*) FROM notification WHERE recipient_id = :id").param("id", account)
			.query(Long.class).single();
	}

	private long incomplete() {
		return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE :id")
			.param("id", "%" + account + "%").query(Long.class).single();
	}

	private static void await(BooleanSupplier condition) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (!condition.getAsBoolean()) {
			if (Instant.now().isAfter(deadline)) {
				throw new AssertionError("Condition not met within 10 seconds");
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ex);
			}
		}
	}

	/** A business fact a module would publish, e.g. a cancelled class. */
	record SomethingHappened(UUID accountId, String key) {
	}

	/** Shaped like a real notification listener in the communication module. */
	static class TestListener {

		private final AtomicInteger failuresLeft = new AtomicInteger();

		private final Notifications notifications;

		TestListener(Notifications notifications) {
			this.notifications = notifications;
		}

		void failNextTimes(int times) {
			failuresLeft.set(times);
		}

		@Async
		@TransactionalEventListener
		void on(SomethingHappened event) {
			if (failuresLeft.getAndDecrement() > 0) {
				throw new IllegalStateException("Simulated failure");
			}
			notifications.notifyNow(List.of(new NotificationRequest(NotificationType.CLASS_REMINDER, event.accountId(),
					NotificationRole.CLIENT, null, null, "TEST_EVENT:" + event.key() + ":" + event.accountId(),
					Map.of("zajęcia", "Test"), Map.of(), Instant.now())));
		}

	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ListenerConfiguration {

		@Bean
		TestListener testListener(Notifications notifications) {
			return new TestListener(notifications);
		}

	}

}
