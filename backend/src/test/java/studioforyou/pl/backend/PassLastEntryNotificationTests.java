package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import studioforyou.pl.backend.communication.push.PushResult;
import studioforyou.pl.backend.communication.push.PushSender;
import studioforyou.pl.backend.membership.BookingEntitlements;

/**
 * The notice that a booking used the last entry of a pass ({@code PASS_LAST_ENTRY_USED}). The
 * notification is created in the background after the booking commits, so the test commits real
 * transactions and cleans up after itself.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PassLastEntryNotificationTests {

	private static final LocalDate CLASS_DAY = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(3);

	@Autowired
	private BookingEntitlements entitlements;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@MockitoBean
	private PushSender pushSender;

	private UUID service;

	private UUID product;

	private UUID variant;

	private UUID adultAccount;

	private UUID adult;

	private UUID parentAccount;

	private UUID parent;

	private UUID child;

	private UUID otherProduct;

	private UUID otherService;

	@BeforeEach
	void setUp() {
		given(pushSender.send(any())).willReturn(PushResult.sent("msg"));
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes)
				VALUES ('Last entry workout', 'WORKOUT', 60) RETURNING id
				""").query(UUID.class).single();
		product = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES ('Karnet 2 wejścia', 'ENTRY_PASS') RETURNING id")
			.query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 2, 30, 100) RETURNING id
				""").param("product", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", product).param("service", service).update();
		adultAccount = addAccount("adult");
		adult = addProfile(adultAccount, "ADULT", "Ala");
		parentAccount = addAccount("parent");
		parent = addProfile(parentAccount, "ADULT", "Monika");
		child = addProfile(null, "CHILD", "Zosia");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", parent).param("child", child).update();
	}

	@AfterEach
	void cleanUp() {
		List<UUID> accounts = List.of(adultAccount, parentAccount);
		List<UUID> customers = List.of(adult, parent, child);
		await(() -> incomplete() == 0);
		jdbc.sql("DELETE FROM notification_delivery WHERE notification_id IN "
				+ "(SELECT id FROM notification WHERE recipient_id IN (:ids))").param("ids", accounts).update();
		jdbc.sql("DELETE FROM notification WHERE recipient_id IN (:ids)").param("ids", accounts).update();
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE customer_id IN (:ids))")
			.param("ids", customers).update();
		jdbc.sql("DELETE FROM reservation WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM customer_pass WHERE customer_id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :service").param("service", service).update();
		if (otherProduct != null) {
			jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :product").param("product", otherProduct)
				.update();
			jdbc.sql("DELETE FROM pass_product_variant WHERE product_id = :product").param("product", otherProduct)
				.update();
			jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", otherProduct).update();
			jdbc.sql("DELETE FROM service_type WHERE id = :service").param("service", otherService).update();
		}
		jdbc.sql("DELETE FROM guardianship WHERE child_id = :child").param("child", child).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)").param("ids", customers).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (:ids)").param("ids", accounts).update();
		jdbc.sql("DELETE FROM account WHERE id IN (:ids)").param("ids", accounts).update();
	}

	@Test
	void notifiesOnceWhenABookingTakesTheLastEntry() {
		UUID pass = addPass(adult, "ACTIVE", 2, CLASS_DAY.plusDays(20));

		book(adult, pass);
		book(adult, pass);
		await(() -> notifications(adultAccount) == 1);

		assertThat(jdbc.sql("SELECT title || ' / ' || body FROM notification WHERE recipient_id = :id")
			.param("id", adultAccount).query(String.class).single())
			.isEqualTo("Wykorzystano ostatnie wejście / W karnecie Karnet 2 wejścia nie zostało już żadne wejście. "
					+ "Kup kolejny, aby dalej zapisywać się na zajęcia.");
		assertThat(jdbc.sql("SELECT dedup_key FROM notification WHERE recipient_id = :id")
			.param("id", adultAccount).query(String.class).single())
			.isEqualTo("PASS_LAST_ENTRY_USED:" + pass + ":" + adultAccount);

		// Cancelling returns the entry; taking it again does not notify a second time.
		UUID last = jdbc.sql("SELECT id FROM reservation WHERE customer_id = :id ORDER BY created_at DESC LIMIT 1")
			.param("id", adult).query(UUID.class).single();
		transactions.executeWithoutResult((status) -> entitlements.release(adult, pass, last, true, adultAccount));
		book(adult, pass);
		await(() -> incomplete() == 0);
		assertThat(notifications(adultAccount)).isEqualTo(1);
	}

	@Test
	void aChildsNoticeGoesToTheGuardian() {
		UUID pass = addPass(child, "ACTIVE", 1, CLASS_DAY.plusDays(20));

		book(child, pass);
		await(() -> notifications(parentAccount) == 1);

		assertThat(jdbc.sql("SELECT title FROM notification WHERE recipient_id = :id")
			.param("id", parentAccount).query(String.class).single())
			.isEqualTo("Zosia: Wykorzystano ostatnie wejście");
	}

	@Test
	void nothingForAnUnlimitedPassOrWhenAFollowingPassIsHeld() {
		UUID unlimited = addPass(adult, "ACTIVE", null, CLASS_DAY.plusDays(20));
		book(adult, unlimited);
		UUID pass = addPass(child, "ACTIVE", 1, CLASS_DAY.plusDays(20));
		addPass(child, "SCHEDULED", 2, CLASS_DAY.plusDays(50));
		book(child, pass);

		await(() -> incomplete() == 0);
		assertThat(notifications(adultAccount)).isZero();
		assertThat(notifications(parentAccount)).isZero();
	}

	@Test
	void aPassForOtherServicesDoesNotCountAsAFollowingPass() {
		UUID pass = addPass(adult, "ACTIVE", 1, CLASS_DAY.plusDays(20));
		// E.g. a personal training package, which does not replace a group class pass.
		UUID personal = addPass(adult, "ACTIVE", 4, CLASS_DAY.plusDays(300));
		jdbc.sql("UPDATE customer_pass SET variant_id = :variant WHERE id = :id")
			.param("variant", otherVariant()).param("id", personal).update();

		book(adult, pass);
		await(() -> notifications(adultAccount) == 1);
	}

	private UUID otherVariant() {
		otherService = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes)
				VALUES ('Last entry personal training', 'PERSONAL', 60) RETURNING id
				""").query(UUID.class).single();
		otherProduct = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES ('Pakiet PT', 'ENTRY_PASS') RETURNING id")
			.query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", otherProduct).param("service", otherService).update();
		return jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 5, 365, 700) RETURNING id
				""").param("product", otherProduct).query(UUID.class).single();
	}

	private void book(UUID customer, UUID pass) {
		OffsetDateTime starts = CLASS_DAY.atTime(18, 0).atOffset(ZoneOffset.UTC);
		UUID event = jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, starts_at, ends_at, capacity)
				VALUES ('CLASS', :service, :starts, :ends, 10) RETURNING id
				""").param("service", service).param("starts", starts).param("ends", starts.plusHours(1))
			.query(UUID.class).single();
		UUID reservation = UUID.randomUUID();
		transactions.executeWithoutResult((status) -> {
			jdbc.sql("""
					INSERT INTO reservation (id, event_id, customer_id, booked_by)
					VALUES (:id, :event, :customer, :actor)
					""").param("id", reservation).param("event", event).param("customer", customer)
				.param("actor", adultAccount).update();
			entitlements.reserve(customer, pass, service, CLASS_DAY, reservation, adultAccount);
		});
	}

	private UUID addPass(UUID customer, String status, Integer entries, LocalDate validUntil) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				    price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'Karnet 2 wejścia', :entries, :entries, 100, :status, :from, :until)
				RETURNING id
				""").param("customer", customer).param("variant", variant)
			.param("entries", entries, java.sql.Types.SMALLINT).param("status", status)
			.param("from", CLASS_DAY.minusDays(5)).param("until", validUntil).query(UUID.class).single();
	}

	private long notifications(UUID account) {
		return jdbc.sql("SELECT count(*) FROM notification WHERE recipient_id = :id").param("id", account)
			.query(Long.class).single();
	}

	/** This test's events whose listener has not finished yet. */
	private long incomplete() {
		return jdbc.sql("""
				SELECT count(*) FROM event_publication
				WHERE completion_date IS NULL
				  AND (serialized_event LIKE :adult OR serialized_event LIKE :child)
				""").param("adult", "%" + adult + "%").param("child", "%" + child + "%")
			.query(Long.class).single();
	}

	private UUID addAccount(String name) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "-" + UUID.randomUUID() + "@last-entry.example.com").query(UUID.class).single();
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
