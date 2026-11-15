package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.WaitlistNotificationCleanup;
import studioforyou.pl.backend.booking.WaitlistPlaceAllocated;
import studioforyou.pl.backend.communication.notification.NotificationDispatcher;
import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;

/** Real queue changes, after-commit events, guardians and delivery retries. */
@SpringBootTest(properties = "booking.waitlist.offer-expiry.enabled=false")
@Import(TestcontainersConfiguration.class)
class WaitlistNotificationTests {

	@Autowired private JdbcClient jdbc;
	@Autowired private GroupClassBookingService bookings;
	@Autowired private GroupClassWaitlistService waitlists;
	@Autowired private TransactionTemplate transactions;
	@Autowired private ApplicationEventPublisher events;
	@Autowired private NotificationDispatcher dispatcher;
	@MockitoBean private PushSender push;
	private UUID account, otherAccount, profile, child, other, service, product, variant, pass, childPass, otherPass, event;

	@BeforeEach
	void setUp() {
		given(push.send(any())).willReturn(PushResult.sent("waitlist-message"));
		account = account(); otherAccount = account();
		profile = customer(account, "Monika"); child = customer(null, "Zosia"); other = customer(otherAccount, "Jan");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", profile).param("child", child).update();
		jdbc.sql("INSERT INTO device_token (account_id, token, platform) VALUES (:id, :token, 'ANDROID')")
			.param("id", account).param("token", "waitlist-" + account).update();
		service = jdbc.sql("INSERT INTO service_type (name, category, default_duration_minutes, max_participants) VALUES ('notification-test', 'GROUP', 60, 8) RETURNING id").query(UUID.class).single();
		product = jdbc.sql("INSERT INTO pass_product (name, kind, audience) VALUES ('notification-test', 'ENTRY_PASS', 'ANY') RETURNING id").query(UUID.class).single();
		variant = jdbc.sql("INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price) VALUES (:id, 8, 30, 120) RETURNING id").param("id", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)").param("product", product).param("service", service).update();
		pass = pass(profile); childPass = pass(child); otherPass = pass(other);
		Instant startsAt = Instant.now().plus(Duration.ofDays(3));
		event = jdbc.sql("INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility) VALUES ('CLASS', :service, 'Pole dance — lista rezerwowa', :start, :end, 1, 'PUBLIC') RETURNING id")
			.param("service", service).param("start", utc(startsAt)).param("end", utc(startsAt.plusSeconds(3600))).query(UUID.class).single();
	}

	@AfterEach
	void cleanUp() {
		if (service == null) return;
		WaitlistNotificationCleanup.removeForService(jdbc, service);
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :id)").param("id", variant).update();
		jdbc.sql("DELETE FROM waitlist_entry WHERE event_id = :id").param("id", event).update();
		jdbc.sql("DELETE FROM reservation WHERE event_id = :id").param("id", event).update();
		jdbc.sql("DELETE FROM booking_policy WHERE event_id = :id").param("id", event).update();
		jdbc.sql("DELETE FROM schedule_event WHERE id = :id").param("id", event).update();
		jdbc.sql("DELETE FROM customer_pass WHERE variant_id = :id").param("id", variant).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :id").param("id", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE id = :id").param("id", variant).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :id").param("id", product).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :id").param("id", service).update();
		jdbc.sql("DELETE FROM guardianship WHERE child_id = :id").param("id", child).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)").param("ids", List.of(profile, child, other)).update();
		jdbc.sql("DELETE FROM device_token WHERE account_id = :id").param("id", account).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (:ids)").param("ids", List.of(account, otherAccount)).update();
		jdbc.sql("DELETE FROM account WHERE id IN (:ids)").param("ids", List.of(account, otherAccount)).update();
		jdbc.sql("UPDATE notification_type SET is_enabled = TRUE WHERE code LIKE 'WAITLIST_PLACE_%'").update();
	}

	@Test
	void automaticPromotionNotifiesOnceAfterBookingAndResubmittedEventsDoNotDuplicateIt() {
		UUID leaving = bookOther();
		UUID entry = waitlists.join(account, profile, event, pass).entry().id();
		assertThat(notificationCount()).isZero();
		cancel(leaving);
		awaitNotifications(1);
		assertThat(code()).isEqualTo("WAITLIST_PLACE_GRANTED");
		assertThat(body()).contains("Zapisano Cię z listy rezerwowej");
		UUID reservation = jdbc.sql("SELECT reservation_id FROM waitlist_entry WHERE id = :id").param("id", entry).query(UUID.class).single();
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id").param("id", pass).query(Integer.class).single()).isEqualTo(7);
		transactions.executeWithoutResult(status -> {
			events.publishEvent(new WaitlistPlaceAllocated(entry, profile, event, reservation, null));
			events.publishEvent(new WaitlistPlaceAllocated(entry, profile, event, reservation, null));
		});
		WaitlistNotificationCleanup.awaitSettled(jdbc, service);
		assertThat(notificationCount()).isEqualTo(1);
		verify(push, times(1)).send(any());
	}

	@Test
	void anOfferGivesTheDeadlineWithoutMakingAReservationOrTakingAnEntry() {
		confirmationMinutes(30);
		UUID leaving = bookOther();
		waitlists.join(account, profile, event, pass);
		cancel(leaving);
		awaitNotifications(1);
		assertThat(code()).isEqualTo("WAITLIST_PLACE_OFFERED");
		assertThat(body()).contains("Potwierdź miejsce do");
		assertThat(jdbc.sql("SELECT payload->>'eventName' FROM notification WHERE id = :id").param("id", notificationId()).query(String.class).single())
			.isEqualTo("Pole dance — lista rezerwowa");
		assertThat(jdbc.sql("SELECT payload->>'eventStartsAt' FROM notification WHERE id = :id").param("id", notificationId()).query(String.class).single())
			.isNotBlank();
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id").param("id", pass).query(Integer.class).single()).isEqualTo(8);
		assertThat(bookings.get(account, profile, event).ownReservation()).isNull();
		assertThat(jdbc.sql("SELECT payload->>'offerExpiresAt' FROM notification WHERE id = :id").param("id", notificationId()).query(String.class).single()).isNotBlank();
	}

	@Test
	void aChildsOfferGoesToItsGuardianAndCarriesTheChildProfile() {
		confirmationMinutes(30);
		UUID leaving = bookOther();
		waitlists.join(account, child, event, childPass);
		cancel(leaving);
		awaitNotifications(1);
		var row = jdbc.sql("SELECT recipient_id, subject_customer_id, title, payload->>'profileId' AS profile FROM notification WHERE id = :id").param("id", notificationId()).query().singleRow();
		assertThat(row).containsEntry("recipient_id", account).containsEntry("subject_customer_id", child)
			.containsEntry("profile", child.toString());
		assertThat(row.get("title").toString()).startsWith("Zosia:");
	}

	@Test
	void rollingBackACancellationPublishesNoNotificationAndDoesNotAllocateThePlace() {
		UUID leaving = bookOther();
		UUID entry = waitlists.join(account, profile, event, pass).entry().id();
		transactions.executeWithoutResult(status -> { cancel(leaving); status.setRollbackOnly(); });
		WaitlistNotificationCleanup.awaitSettled(jdbc, service);
		assertThat(notificationCount()).isZero();
		assertThat(jdbc.sql("SELECT status FROM waitlist_entry WHERE id = :id").param("id", entry).query(String.class).single()).isEqualTo("WAITING");
		verifyNoInteractions(push);
	}

	@Test
	void switchedOffNotificationsDoNotPreventAutomaticPromotion() {
		jdbc.sql("UPDATE notification_type SET is_enabled = FALSE WHERE code = 'WAITLIST_PLACE_GRANTED'").update();
		UUID leaving = bookOther();
		waitlists.join(account, profile, event, pass);
		cancel(leaving);
		WaitlistNotificationCleanup.awaitSettled(jdbc, service);
		assertThat(notificationCount()).isZero();
		assertThat(bookings.get(account, profile, event).ownReservation()).isNotNull();
		verifyNoInteractions(push);
	}

	@Test
	void failedPushKeepsTheReservationAndCanBeRetried() {
		given(push.send(any())).willReturn(PushResult.failed("temporary"));
		UUID leaving = bookOther();
		waitlists.join(account, profile, event, pass);
		cancel(leaving);
		awaitNotifications(1);
		assertThat(bookings.get(account, profile, event).ownReservation()).isNotNull();
		given(push.send(any())).willReturn(PushResult.sent("retry"));
		assertThat(dispatcher.dispatch(List.of(notificationId()))).isEqualTo(1);
		assertThat(notificationCount()).isEqualTo(1);
		verify(push, times(2)).send(any());
	}

	@Test
	void aFailedOfferIsNotPushedAfterItsDeadlineAndIsRemovedFromRetryCandidates() {
		given(push.send(any())).willReturn(PushResult.failed("temporary"));
		confirmationMinutes(30);
		UUID leaving = bookOther();
		waitlists.join(account, profile, event, pass);
		cancel(leaving);
		awaitNotifications(1);
		jdbc.sql("UPDATE waitlist_entry SET offer_expires_at = now() - interval '1 second' WHERE event_id = :id").param("id", event).update();
		assertThat(dispatcher.dispatch(List.of(notificationId()))).isZero();
		assertThat(jdbc.sql("SELECT status FROM notification_delivery WHERE notification_id = :id ORDER BY attempted_at DESC LIMIT 1").param("id", notificationId()).query(String.class).single()).isEqualTo("SKIPPED");
		verify(push, times(1)).send(any());
	}

	@Test
	void retryRechecksTheGuardiansAccess() {
		given(push.send(any())).willReturn(PushResult.failed("temporary"));
		confirmationMinutes(30);
		UUID leaving = bookOther();
		waitlists.join(account, child, event, childPass);
		cancel(leaving);
		awaitNotifications(1);
		jdbc.sql("UPDATE guardianship SET ended_at = now() WHERE child_id = :id").param("id", child).update();
		assertThat(dispatcher.dispatch(List.of(notificationId()))).isZero();
		verify(push, times(1)).send(any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"EXPIRED", "CANCELLED", "PROMOTED"})
	void obsoleteOffersAreIgnoredWhenTheirEventIsProcessedLater(String state) {
		Instant deadline = Instant.now().plusSeconds(1800);
		UUID entry = jdbc.sql("INSERT INTO waitlist_entry (event_id, customer_id, added_by, status, customer_pass_id, offer_expires_at) VALUES (:event, :profile, :account, :status, :pass, :expires) RETURNING id")
			.param("event", event).param("profile", profile).param("account", account).param("status", state).param("pass", pass).param("expires", utc(deadline)).query(UUID.class).single();
		transactions.executeWithoutResult(status -> events.publishEvent(new WaitlistPlaceAllocated(entry, profile, event, null, deadline)));
		WaitlistNotificationCleanup.awaitSettled(jdbc, service);
		assertThat(notificationCount()).isZero();
		verifyNoInteractions(push);
	}

	private UUID account() {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id").param("email", UUID.randomUUID() + "@waitlist-notification.test").query(UUID.class).single();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'").param("id", id).update();
		return id;
	}

	private UUID customer(UUID accountId, String name) {
		return jdbc.sql("INSERT INTO customer_profile (kind, first_name, last_name, birth_date, account_id) VALUES (:kind, :name, 'Notification', CURRENT_DATE - INTERVAL '20 years', :account) RETURNING id")
			.param("kind", accountId == null ? "CHILD" : "ADULT").param("name", name).param("account", accountId, java.sql.Types.OTHER).query(UUID.class).single();
	}

	private UUID pass(UUID customer) {
		return jdbc.sql("INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining, price_paid, status, valid_from, valid_until) VALUES (:customer, :variant, 'notification-test', 8, 8, 120, 'ACTIVE', CURRENT_DATE, CURRENT_DATE + 29) RETURNING id")
			.param("customer", customer).param("variant", variant).query(UUID.class).single();
	}

	private UUID bookOther() { return bookings.book(otherAccount, other, event, otherPass).reservation().id(); }
	private void cancel(UUID reservation) { bookings.cancel(otherAccount, other, reservation, new GroupClassDtos.CancelRequest(0, true)); }
	private void confirmationMinutes(int minutes) { jdbc.sql("INSERT INTO booking_policy (scope, event_id, waitlist_confirm_minutes) VALUES ('EVENT', :id, :minutes)").param("id", event).param("minutes", minutes).update(); }
	private long notificationCount() { return jdbc.sql("SELECT count(*) FROM notification WHERE payload->>'eventId' = :id").param("id", event.toString()).query(Long.class).single(); }
	private UUID notificationId() { return jdbc.sql("SELECT id FROM notification WHERE payload->>'eventId' = :id").param("id", event.toString()).query(UUID.class).single(); }
	private String code() { return jdbc.sql("SELECT t.code FROM notification n JOIN notification_type t ON t.id = n.notification_type_id WHERE n.id = :id").param("id", notificationId()).query(String.class).single(); }
	private String body() { return jdbc.sql("SELECT body FROM notification WHERE id = :id").param("id", notificationId()).query(String.class).single(); }
	private void awaitNotifications(long count) {
		await().atMost(Duration.ofSeconds(10)).until(() -> notificationCount() == count);
		WaitlistNotificationCleanup.awaitSettled(jdbc, service);
	}
	private static OffsetDateTime utc(Instant at) { return at.atOffset(ZoneOffset.UTC); }
}
