package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import studioforyou.pl.backend.NotificationCleanup;
import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.booking.ClassAutoCancellation;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ReservationResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The automatic cancellation of classes too few participants booked (#155) on real PostgreSQL
 * transactions: what happens to the class, its reservations, the passes and the waiting list, and
 * who is told. The notifications are created in the background after the commit.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClassAutoCancellationTests {

	private static final String EMAIL_DOMAIN = "@auto-cancel.test";

	private static final String ACCOUNTS = "SELECT id FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'";

	@Autowired
	private ClassAutoCancellation autoCancellation;

	@Autowired
	private GroupClassBookingService bookings;

	@Autowired
	private JdbcClient jdbc;

	private UUID service;

	private UUID product;

	private UUID variant;

	private UUID trainer;

	private UUID trainerAccount;

	private UUID annaAccount;

	private UUID anna;

	private UUID ewaAccount;

	private UUID ewa;

	private UUID olaAccount;

	private UUID ola;

	@BeforeEach
	void setUp() {
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, max_participants)
				VALUES ('Auto-cancel yoga', 'GROUP', 60, 12) RETURNING id
				""").query(UUID.class).single();
		// Checked 3 hours before the start; at least 3 participants.
		jdbc.sql("""
				INSERT INTO booking_policy (scope, service_type_id, auto_cancel_enabled, auto_cancel_min_participants,
				    auto_cancel_hours_before)
				VALUES ('SERVICE_TYPE', :service, TRUE, 3, 3)
				""").param("service", service).update();
		product = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES ('Auto-cancel pass', 'ENTRY_PASS') RETURNING id")
			.query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 120) RETURNING id
				""").param("product", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", product).param("service", service).update();
		trainerAccount = account("trainer", "TRAINER");
		trainer = jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name, calendar_color, status)
				VALUES (:account, 'Iga', 'Trener', '#E91E63', 'ACTIVE') RETURNING id
				""").param("account", trainerAccount).query(UUID.class).single();
		annaAccount = account("anna", "CLIENT");
		anna = customer(annaAccount, "Anna");
		ewaAccount = account("ewa", "CLIENT");
		ewa = customer(ewaAccount, "Ewa");
		olaAccount = account("ola", "CLIENT");
		ola = customer(olaAccount, "Ola");
	}

	@AfterEach
	void cleanUp() {
		NotificationCleanup.deleteNotifications(jdbc, ACCOUNTS);
		String events = "SELECT id FROM schedule_event WHERE service_type_id = :service";
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :variant)")
			.param("variant", variant).update();
		jdbc.sql("DELETE FROM waitlist_entry WHERE event_id IN (" + events + ")").param("service", service).update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + events + ")").param("service", service).update();
		jdbc.sql("DELETE FROM event_staff WHERE event_id IN (" + events + ")").param("service", service).update();
		jdbc.sql("DELETE FROM booking_policy WHERE service_type_id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM customer_pass WHERE variant_id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM staff_profile WHERE id = :id").param("id", trainer).update();
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (" + ACCOUNTS + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + ACCOUNTS + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'").update();
	}

	@Test
	void cancelsAClassWithTooFewParticipantsReturnsTheirEntriesAndTellsEveryone() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		UUID event = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(1)), false);
		UUID annaPass = pass(anna);
		UUID ewaPass = pass(ewa);
		ReservationResponse annaPlace = bookings.book(annaAccount, anna, event, annaPass).reservation();
		bookings.book(ewaAccount, ewa, event, ewaPass);
		UUID waiting = jdbc.sql("""
				INSERT INTO waitlist_entry (event_id, customer_id, added_by) VALUES (:event, :customer, :account)
				RETURNING id
				""").param("event", event).param("customer", ola).param("account", olaAccount)
			.query(UUID.class).single();

		assertThat(autoCancellation.cancelDue(now)).isEqualTo(1);

		assertThat(jdbc.sql("SELECT status || ' ' || cancel_reason FROM schedule_event WHERE id = :id")
			.param("id", event).query(String.class).single()).isEqualTo("CANCELLED TOO_FEW_PARTICIPANTS");
		assertThat(jdbc.sql("""
				SELECT status || ' ' || cancel_kind || ' ' || entry_returned FROM reservation WHERE event_id = :id
				""").param("id", event).query(String.class).list())
			.containsExactly("CANCELLED STUDIO true", "CANCELLED STUDIO true");
		assertThat(balance(annaPass)).isEqualTo(8);
		assertThat(balance(ewaPass)).isEqualTo(8);
		assertThat(jdbc.sql("SELECT kind FROM pass_ledger WHERE reservation_id = :id ORDER BY occurred_at, kind")
			.param("id", annaPlace.id()).query(String.class).list()).containsExactly("ENTRY_RESERVED", "ENTRY_RETURNED");
		assertThat(jdbc.sql("SELECT status FROM waitlist_entry WHERE id = :id").param("id", waiting)
			.query(String.class).single()).isEqualTo("CANCELLED");

		await(() -> notifications(trainerAccount).size() == 1 && notifications(annaAccount).size() == 1
				&& notifications(ewaAccount).size() == 1 && notifications(olaAccount).size() == 1);
		String when = ", " + day(event) + " " + time(event) + ".";
		assertThat(notifications(annaAccount)).containsExactly("CLASS_CANCELLED / Zajęcia odwołane / Odwołano: "
				+ "Auto-cancel class" + when + " Powód: zbyt mało zapisanych osób.");
		assertThat(notifications(olaAccount)).hasSize(1);
		assertThat(notifications(trainerAccount)).containsExactly("CLASS_AUTO_CANCELLED / Zajęcia odwołane automatycznie"
				+ " / Odwołano: Auto-cancel class" + when + " Zapisanych osób: 2, wymagane minimum: 3.");

		// The next job run finds nothing more to do.
		assertThat(autoCancellation.cancelDue(now.plus(Duration.ofMinutes(5)))).isZero();
	}

	@Test
	void keepsClassesWithEnoughParticipantsForcedToRunOrNotDueYet() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		UUID full = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(1)), false);
		reserve(full, anna, annaAccount);
		reserve(full, ewa, ewaAccount);
		reserve(full, ola, olaAccount);
		UUID forced = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(2)), true);
		reserve(forced, anna, annaAccount);
		UUID later = event(now.plus(Duration.ofHours(5)), false);
		// Checked at the moment only: a job run that missed it by more than 30 minutes keeps the class.
		UUID missed = event(now.plus(Duration.ofHours(2)), false);

		assertThat(autoCancellation.cancelDue(now)).isZero();

		assertThat(jdbc.sql("SELECT DISTINCT status FROM schedule_event WHERE id IN (:ids)")
			.param("ids", List.of(full, forced, later, missed)).query(String.class).list()).containsExactly("SCHEDULED");
		assertThat(jdbc.sql("SELECT DISTINCT status FROM reservation WHERE event_id IN (:ids)")
			.param("ids", List.of(full, forced)).query(String.class).list()).containsExactly("ACTIVE");

		// The later class is checked once its moment comes.
		assertThat(autoCancellation.cancelDue(now.plus(Duration.ofHours(2)))).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM schedule_event WHERE id = :id").param("id", later)
			.query(String.class).single()).isEqualTo("CANCELLED");
	}

	@Test
	void anEventPolicyCanSwitchTheRuleOff() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		UUID event = event(now.plus(Duration.ofHours(3)).minus(Duration.ofMinutes(1)), false);
		jdbc.sql("INSERT INTO booking_policy (scope, event_id, auto_cancel_enabled) VALUES ('EVENT', :event, FALSE)")
			.param("event", event).update();

		assertThat(autoCancellation.cancelDue(now)).isZero();
		jdbc.sql("DELETE FROM booking_policy WHERE event_id = :event").param("event", event).update();
	}

	private UUID account(String name, String role) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + EMAIL_DOMAIN).query(UUID.class).single();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id).param("role", role).update();
		return id;
	}

	private UUID customer(UUID account, String firstName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, birth_date, account_id)
				VALUES ('ADULT', :firstName, 'Uczestniczka', :birthDate, :account) RETURNING id
				""").param("firstName", firstName).param("birthDate", LocalDate.now().minusYears(30))
			.param("account", account).query(UUID.class).single();
	}

	private UUID pass(UUID customer) {
		LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				    price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'Auto-cancel pass', 8, 8, 120, 'ACTIVE', :from, :until) RETURNING id
				""").param("customer", customer).param("variant", variant).param("from", today.minusDays(1))
			.param("until", today.plusDays(29)).query(UUID.class).single();
	}

	private UUID event(Instant startsAt, boolean forceRun) {
		UUID id = jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility,
				    force_run)
				VALUES ('CLASS', :service, 'Auto-cancel class', :startsAt, :endsAt, 12, 'PUBLIC', :forceRun) RETURNING id
				""").param("service", service).param("startsAt", utc(startsAt))
			.param("endsAt", utc(startsAt.plus(Duration.ofHours(1)))).param("forceRun", forceRun)
			.query(UUID.class).single();
		jdbc.sql("INSERT INTO event_staff (event_id, staff_id, role) VALUES (:event, :staff, 'LEAD')")
			.param("event", id).param("staff", trainer).update();
		return id;
	}

	private void reserve(UUID event, UUID customer, UUID account) {
		jdbc.sql("INSERT INTO reservation (event_id, customer_id, booked_by) VALUES (:event, :customer, :account)")
			.param("event", event).param("customer", customer).param("account", account).update();
	}

	private int balance(UUID pass) {
		return jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id").param("id", pass)
			.query(Integer.class).single();
	}

	/** "dziś" or "jutro": the class starts within a few hours. */
	private String day(UUID event) {
		return jdbc.sql("SELECT (starts_at AT TIME ZONE 'Europe/Warsaw')::date = (now() AT TIME ZONE 'Europe/Warsaw')::date "
				+ "FROM schedule_event WHERE id = :id").param("id", event).query(Boolean.class).single() ? "dziś" : "jutro";
	}

	private String time(UUID event) {
		return jdbc.sql("SELECT to_char(starts_at AT TIME ZONE 'Europe/Warsaw', 'HH24:MI') FROM schedule_event "
				+ "WHERE id = :id").param("id", event).query(String.class).single();
	}

	private List<String> notifications(UUID account) {
		return jdbc.sql("""
				SELECT t.code || ' / ' || n.title || ' / ' || n.body FROM notification n
				JOIN notification_type t ON t.id = n.notification_type_id
				WHERE n.recipient_id = :id ORDER BY n.created_at, n.id
				""").param("id", account).query(String.class).list();
	}

	private static OffsetDateTime utc(Instant value) {
		return value.atOffset(ZoneOffset.UTC);
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

}
