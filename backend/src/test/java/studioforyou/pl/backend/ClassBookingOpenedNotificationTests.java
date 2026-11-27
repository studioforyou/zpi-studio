package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.interest.ClassBookingOpenedNotifications;
import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;

/**
 * Notifications about classes whose booking opened ({@code CLASS_BOOKING_OPENED}, #143), against
 * real PostgreSQL. The job is called with the moment booking opens instead of waiting for it.
 * Only the notifications of the test's own accounts are checked, as seed data may have classes
 * opening at the same time.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ClassBookingOpenedNotificationTests {

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	/** Booking of the test service's classes opens this many days before the start. */
	private static final int OPENS_DAYS_BEFORE = 7;

	@Autowired
	private ClassBookingOpenedNotifications notifications;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private PushSender pushSender;

	private UUID service;

	/** The moment booking of {@link #event} opens. */
	private Instant opens;

	private UUID event;

	@BeforeEach
	void setUp() {
		given(pushSender.send(any())).willReturn(PushResult.sent("msg"));
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, max_participants)
				VALUES ('Booking opened yoga', 'GROUP', 60, 12) RETURNING id
				""").query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO booking_policy (scope, service_type_id, booking_opens_days_before)
				VALUES ('SERVICE_TYPE', :service, :days)
				""").param("service", service).param("days", OPENS_DAYS_BEFORE).update();
		opens = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);
		event = event(opens.plus(Duration.ofDays(OPENS_DAYS_BEFORE)), 12);
	}

	@Test
	void tellsInterestedParticipantsOnceWhenBookingOpens() {
		UUID annaAccount = account("anna");
		UUID anna = profile(annaAccount, "ADULT", "Anna");
		interest(anna);
		UUID ewaAccount = account("ewa");
		UUID ewa = profile(ewaAccount, "ADULT", "Ewa");
		attended(ewa, opens.minus(Duration.ofDays(20)), "UNMARKED");

		assertThat(notifications.sendDue(opens.minusSeconds(60))).isZero();
		assertThat(notifications.sendDue(opens)).isGreaterThanOrEqualTo(2);
		// A later job run in the grace period creates nothing new.
		notifications.sendDue(opens.plus(Duration.ofMinutes(5)));

		assertThat(titles(annaAccount)).containsExactly("Ruszyły zapisy na zajęcia");
		assertThat(titles(ewaAccount)).containsExactly("Ruszyły zapisy na zajęcia");
		String day = DateTimeFormatter.ofPattern("EEEE d.MM", Locale.forLanguageTag("pl-PL"))
			.format(opens.plus(Duration.ofDays(OPENS_DAYS_BEFORE)).atZone(WARSAW));
		String time = DateTimeFormatter.ofPattern("HH:mm").format(opens.atZone(WARSAW));
		assertThat(bodies(annaAccount)).containsExactly("Booking opened class: " + day + ", godz. " + time
				+ ", studio. Zapisz się, zanim zabraknie miejsc.");
		assertThat(jdbc.sql("SELECT dedup_key FROM notification WHERE recipient_id = :id")
			.param("id", annaAccount).query(String.class).single())
			.isEqualTo("CLASS_BOOKING_OPENED:" + event + ":" + anna + ":" + annaAccount);
		assertThat(jdbc.sql("SELECT payload->>'eventId' FROM notification WHERE recipient_id = :id")
			.param("id", ewaAccount).query(String.class).single()).isEqualTo(event.toString());
		assertThat(jdbc.sql("SELECT scheduled_for FROM notification WHERE recipient_id = :id")
			.param("id", annaAccount).query(OffsetDateTime.class).single().toInstant()).isEqualTo(opens);
	}

	@Test
	void aChildsNotificationGoesToItsGuardianWithTheChildsName() {
		UUID parentAccount = account("parent");
		UUID parent = profile(parentAccount, "ADULT", "Monika");
		UUID child = profile(null, "CHILD", "Zosia");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", parent).param("child", child).update();
		interest(child);

		notifications.sendDue(opens);

		assertThat(titles(parentAccount)).containsExactly("Zosia: Ruszyły zapisy na zajęcia");
		assertThat(jdbc.sql("SELECT subject_customer_id FROM notification WHERE recipient_id = :id")
			.param("id", parentAccount).query(UUID.class).single()).isEqualTo(child);
	}

	@Test
	void skipsParticipantsTakingPartAbsentOrLongAgoAndOtherServices() {
		UUID bookedAccount = account("booked");
		UUID booked = profile(bookedAccount, "ADULT", "Basia");
		interest(booked);
		reserve(event, booked, "UNMARKED");
		UUID waitingAccount = account("waiting");
		UUID waiting = profile(waitingAccount, "ADULT", "Kasia");
		interest(waiting);
		jdbc.sql("INSERT INTO waitlist_entry (event_id, customer_id, added_by) VALUES (:event, :customer, :account)")
			.param("event", event).param("customer", waiting).param("account", waitingAccount).update();
		UUID absentAccount = account("absent");
		attended(profile(absentAccount, "ADULT", "Ola"), opens.minus(Duration.ofDays(10)), "ABSENT");
		UUID longAgoAccount = account("long-ago");
		attended(profile(longAgoAccount, "ADULT", "Ula"), opens.minus(Duration.ofDays(61)), "PRESENT");
		UUID otherAccount = account("other");
		UUID other = profile(otherAccount, "ADULT", "Iza");
		UUID pilates = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes)
				VALUES ('Booking opened pilates', 'GROUP', 60) RETURNING id
				""").query(UUID.class).single();
		jdbc.sql("INSERT INTO customer_interest (customer_id, service_type_id) VALUES (:customer, :service)")
			.param("customer", other).param("service", pilates).update();

		notifications.sendDue(opens);

		assertThat(titles(bookedAccount)).isEmpty();
		assertThat(titles(waitingAccount)).isEmpty();
		assertThat(titles(absentAccount)).isEmpty();
		assertThat(titles(longAgoAccount)).isEmpty();
		assertThat(titles(otherAccount)).isEmpty();
	}

	@Test
	void skipsAFullClassAndASwitchedOffType() {
		UUID annaAccount = account("anna");
		UUID anna = profile(annaAccount, "ADULT", "Anna");
		interest(anna);
		jdbc.sql("UPDATE schedule_event SET capacity = 1 WHERE id = :id").param("id", event).update();
		reserve(event, profile(account("booked"), "ADULT", "Basia"), "UNMARKED");

		notifications.sendDue(opens);
		assertThat(titles(annaAccount)).isEmpty();

		jdbc.sql("UPDATE schedule_event SET capacity = 2 WHERE id = :id").param("id", event).update();
		jdbc.sql("UPDATE notification_type SET is_enabled = FALSE WHERE code = 'CLASS_BOOKING_OPENED'").update();
		assertThat(notifications.sendDue(opens)).isZero();
		jdbc.sql("UPDATE notification_type SET is_enabled = TRUE WHERE code = 'CLASS_BOOKING_OPENED'").update();
		notifications.sendDue(opens);
		assertThat(titles(annaAccount)).hasSize(1);
	}

	@Test
	void aClassAddedWhenItsBookingIsAlreadyOpenIsAnnouncedWhenAdded() {
		UUID annaAccount = account("anna");
		interest(profile(annaAccount, "ADULT", "Anna"));
		// Booking opened 3 days ago; the class was added just now.
		Instant added = opens;
		UUID late = event(added.plus(Duration.ofDays(OPENS_DAYS_BEFORE - 3)), 12);
		jdbc.sql("UPDATE schedule_event SET created_at = :at WHERE id = :id")
			.param("at", added.atOffset(ZoneOffset.UTC)).param("id", late).update();
		jdbc.sql("UPDATE schedule_event SET status = 'CANCELLED' WHERE id = :id").param("id", event).update();

		notifications.sendDue(added.plus(Duration.ofMinutes(2)));

		assertThat(jdbc.sql("SELECT payload->>'eventId' FROM notification WHERE recipient_id = :id")
			.param("id", annaAccount).query(String.class).list()).containsExactly(late.toString());
	}

	private UUID event(Instant startsAt, int capacity) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility)
				VALUES ('CLASS', :service, 'Booking opened class', :startsAt, :endsAt, :capacity, 'PUBLIC') RETURNING id
				""").param("service", service).param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
			.param("endsAt", startsAt.plus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC)).param("capacity", capacity)
			.query(UUID.class).single();
	}

	/** A past class of the test service the participant had a place in. */
	private void attended(UUID customer, Instant startsAt, String attendance) {
		reserve(event(startsAt, 12), customer, attendance);
	}

	private void reserve(UUID eventId, UUID customer, String attendance) {
		UUID bookedBy = jdbc.sql("SELECT id FROM account WHERE email LIKE '%@booking-opened.example.com' LIMIT 1")
			.query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by, attendance)
				VALUES (:event, :customer, :bookedBy, :attendance)
				""").param("event", eventId).param("customer", customer).param("bookedBy", bookedBy)
			.param("attendance", attendance).update();
	}

	private void interest(UUID customer) {
		jdbc.sql("INSERT INTO customer_interest (customer_id, service_type_id) VALUES (:customer, :service)")
			.param("customer", customer).param("service", service).update();
	}

	private List<String> titles(UUID account) {
		return jdbc.sql("SELECT title FROM notification WHERE recipient_id = :id").param("id", account)
			.query(String.class).list();
	}

	private List<String> bodies(UUID account) {
		return jdbc.sql("SELECT body FROM notification WHERE recipient_id = :id").param("id", account)
			.query(String.class).list();
	}

	private UUID account(String name) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@booking-opened.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", id).update();
		return id;
	}

	private UUID profile(UUID account, String kind, String firstName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, :firstName, 'Tester') RETURNING id
				""").param("account", account, java.sql.Types.OTHER).param("kind", kind).param("firstName", firstName)
			.query(UUID.class).single();
	}

}
