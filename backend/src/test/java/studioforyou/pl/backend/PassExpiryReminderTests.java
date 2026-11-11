package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.pass.PassExpiryReminders;
import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;

/**
 * Reminders before a pass expires ({@code PASS_EXPIRING}), against real PostgreSQL. The job is
 * called with the moment a reminder is due instead of waiting for it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PassExpiryReminderTests {

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	private static final LocalDate LAST_DAY = LocalDate.now(WARSAW).plusDays(10);

	/** 9:00 on the last day, the moment the default reminder times count back from. */
	private static final Instant ANCHOR = LAST_DAY.atTime(LocalTime.of(9, 0)).atZone(WARSAW).toInstant();

	private static final Instant THREE_DAYS_BEFORE = ANCHOR.minus(Duration.ofDays(3));

	private static final Instant ONE_DAY_BEFORE = ANCHOR.minus(Duration.ofDays(1));

	@Autowired
	private PassExpiryReminders reminders;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private PushSender pushSender;

	private UUID product;

	private UUID variant;

	private UUID adultAccount;

	private UUID adult;

	private UUID parentAccount;

	private UUID child;

	@BeforeEach
	void setUp() {
		given(pushSender.send(any())).willReturn(PushResult.sent("msg"));
		product = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES ('Karnet 8 wejść', 'ENTRY_PASS') RETURNING id")
			.query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 200) RETURNING id
				""").param("product", product).query(UUID.class).single();
		adultAccount = addAccount("adult");
		adult = addProfile(adultAccount, "ADULT", "Ala");
		parentAccount = addAccount("parent");
		UUID parent = addProfile(parentAccount, "ADULT", "Monika");
		child = addProfile(null, "CHILD", "Zosia");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", parent).param("child", child).update();
	}

	@Test
	void remindsAtEachReminderTimeBefore9OnTheLastDay() {
		UUID pass = addPass(adult, "ACTIVE", 3, LAST_DAY);

		assertThat(reminders.sendDue(THREE_DAYS_BEFORE.minusSeconds(60))).isZero();
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isEqualTo(1);
		// A later job run in the grace period creates nothing new.
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE.plus(Duration.ofMinutes(5)))).isZero();
		assertThat(reminders.sendDue(ONE_DAY_BEFORE.plus(Duration.ofMinutes(10)))).isEqualTo(1);

		assertThat(titles(adultAccount)).containsOnly("Karnet wkrótce wygaśnie");
		assertThat(bodies(adultAccount)).containsOnly("Karnet 8 wejść jest ważny do "
				+ LAST_DAY.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
				+ ". Kup kolejny, aby dalej zapisywać się na zajęcia.");
		assertThat(jdbc.sql("SELECT dedup_key FROM notification WHERE recipient_id = :id ORDER BY scheduled_for")
			.param("id", adultAccount).query(String.class).list())
			.containsExactly("PASS_EXPIRING:" + pass + ":" + LAST_DAY + ":4320:" + adultAccount,
					"PASS_EXPIRING:" + pass + ":" + LAST_DAY + ":1440:" + adultAccount);
		assertThat(jdbc.sql("SELECT payload->>'passId' FROM notification WHERE recipient_id = :id")
			.param("id", adultAccount).query(String.class).list()).containsOnly(pass.toString());
	}

	@Test
	void aChildsReminderGoesToTheGuardianWithTheChildsName() {
		addPass(child, "SCHEDULED", null, LAST_DAY);

		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isEqualTo(1);

		assertThat(titles(parentAccount)).containsExactly("Zosia: Karnet wkrótce wygaśnie");
		assertThat(jdbc.sql("SELECT subject_customer_id FROM notification WHERE recipient_id = :id")
			.param("id", parentAccount).query(UUID.class).single()).isEqualTo(child);
	}

	@Test
	void skipsPassesWithNoEntriesLeftOrNotUsableAndPassesWithAFollowingOne() {
		addPass(adult, "ACTIVE", 0, LAST_DAY);
		addPass(adult, "CANCELLED", 5, LAST_DAY);
		addPass(child, "ACTIVE", 5, LAST_DAY);
		addPass(child, "SCHEDULED", 8, LAST_DAY.plusDays(30));

		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isZero();
	}

	@Test
	void aFollowingPassCountsOnlyWhenItIsUsable() {
		addPass(adult, "ACTIVE", 5, LAST_DAY);
		addPass(adult, "PENDING_PAYMENT", 8, LAST_DAY.plusDays(30));
		addPass(adult, "ACTIVE", 0, LAST_DAY.plusDays(30));
		addPass(adult, "ACTIVE", 8, LAST_DAY);

		// The two passes ending on the same day do not follow each other.
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isEqualTo(2);
	}

	@Test
	void aPassMustCoverTheSameServicesToCountAsFollowing() {
		UUID pole = addService("Pole dance");
		UUID personal = addService("Personal training");
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", product).param("service", pole).update();
		UUID personalVariant = addVariant("Pakiet PT", personal);
		UUID openVariant = addVariant("Karnet Open", pole, personal);

		addPass(adult, "ACTIVE", 2, LAST_DAY);
		UUID package_ = addPass(adult, "ACTIVE", 4, LAST_DAY.plusDays(300));
		setVariant(package_, personalVariant);
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isEqualTo(1);

		addPass(child, "ACTIVE", 2, LAST_DAY);
		UUID open = addPass(child, "SCHEDULED", null, LAST_DAY.plusDays(30));
		setVariant(open, openVariant);
		assertThat(reminders.sendDue(ONE_DAY_BEFORE)).isEqualTo(1);
		assertThat(titles(parentAccount)).isEmpty();
	}

	@Test
	void skipsAPassAssignedAfterTheReminderTimeAndASwitchedOffType() {
		UUID late = addPass(adult, "ACTIVE", 5, LAST_DAY);
		jdbc.sql("UPDATE customer_pass SET created_at = :at WHERE id = :id")
			.param("at", THREE_DAYS_BEFORE.plusSeconds(1).atOffset(ZoneOffset.UTC)).param("id", late).update();
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE.plusSeconds(60))).isZero();

		addPass(child, "ACTIVE", 5, LAST_DAY);
		jdbc.sql("UPDATE notification_type SET is_enabled = FALSE WHERE code = 'PASS_EXPIRING'").update();
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isZero();
		jdbc.sql("UPDATE notification_type SET is_enabled = TRUE WHERE code = 'PASS_EXPIRING'").update();
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isEqualTo(1);
	}

	@Test
	void anExtendedPassIsRemindedAgainBeforeItsNewLastDay() {
		UUID pass = addPass(adult, "ACTIVE", 5, LAST_DAY);
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE)).isEqualTo(1);

		jdbc.sql("UPDATE customer_pass SET valid_until = :until WHERE id = :id")
			.param("until", LAST_DAY.plusDays(7)).param("id", pass).update();
		assertThat(reminders.sendDue(ONE_DAY_BEFORE)).isZero();
		assertThat(reminders.sendDue(THREE_DAYS_BEFORE.plus(Duration.ofDays(7)))).isEqualTo(1);
	}

	private UUID addPass(UUID customer, String status, Integer entries, LocalDate validUntil) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				    price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'Karnet 8 wejść', :entries, :entries, 200, :status, :from, :until)
				RETURNING id
				""").param("customer", customer).param("variant", variant)
			.param("entries", entries, java.sql.Types.SMALLINT).param("status", status)
			.param("from", validUntil.minusDays(30)).param("until", validUntil).query(UUID.class).single();
	}

	private UUID addService(String name) {
		return jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes)
				VALUES (:name, 'WORKOUT', 60) RETURNING id
				""").param("name", name).query(UUID.class).single();
	}

	private UUID addVariant(String name, UUID... services) {
		UUID other = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES (:name, 'ENTRY_PASS') RETURNING id")
			.param("name", name).query(UUID.class).single();
		for (UUID service : services) {
			jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
				.param("product", other).param("service", service).update();
		}
		return jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 5, 365, 700) RETURNING id
				""").param("product", other).query(UUID.class).single();
	}

	private void setVariant(UUID pass, UUID variant) {
		jdbc.sql("UPDATE customer_pass SET variant_id = :variant WHERE id = :id").param("variant", variant)
			.param("id", pass).update();
	}

	private List<String> titles(UUID account) {
		return jdbc.sql("SELECT title FROM notification WHERE recipient_id = :id").param("id", account)
			.query(String.class).list();
	}

	private List<String> bodies(UUID account) {
		return jdbc.sql("SELECT body FROM notification WHERE recipient_id = :id").param("id", account)
			.query(String.class).list();
	}

	private UUID addAccount(String name) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@pass-expiry.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", id).update();
		return id;
	}

	private UUID addProfile(UUID account, String kind, String firstName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, :firstName, 'Tester') RETURNING id
				""").param("account", account, java.sql.Types.OTHER).param("kind", kind).param("firstName", firstName)
			.query(UUID.class).single();
	}

}
