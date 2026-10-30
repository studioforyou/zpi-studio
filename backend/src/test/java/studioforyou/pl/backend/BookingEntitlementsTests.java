package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.internal.CustomerPassService;
import studioforyou.pl.backend.membership.internal.CustomerPassService.EntryCorrection;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/** Real PostgreSQL transactions exercise balance, ledger and competing allocations. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BookingEntitlementsTests {

	private static final LocalDate CLASS_DAY = LocalDate.of(2027, 2, 10);

	@Autowired
	private BookingEntitlements entitlements;

	@Autowired
	private CustomerPassService customerPasses;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID actor;

	private UUID customer;

	private UUID service;

	private UUID product;

	private UUID variant;

	private TransactionTemplate transaction;

	@BeforeEach
	void setUp() {
		transaction = new TransactionTemplate(transactionManager);
		actor = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", UUID.randomUUID() + "@entitlement.example.com").query(UUID.class).single();
		customer = jdbc.sql("""
				INSERT INTO customer_profile (account_id, first_name, last_name)
				VALUES (:actor, 'Pass', 'Participant') RETURNING id
				""").param("actor", actor).query(UUID.class).single();
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes)
				VALUES ('Group workout', 'WORKOUT', 60) RETURNING id
				""").query(UUID.class).single();
		product = jdbc.sql("INSERT INTO pass_product (name, kind) VALUES ('Group pass', 'ENTRY_PASS') RETURNING id")
			.query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 200) RETURNING id
				""").param("product", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", product).param("service", service).update();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE customer_id = :customer)")
			.param("customer", customer).update();
		jdbc.sql("DELETE FROM pass_suspension WHERE pass_id IN (SELECT id FROM customer_pass WHERE customer_id = :customer)")
			.param("customer", customer).update();
		jdbc.sql("DELETE FROM reservation WHERE customer_id = :customer").param("customer", customer).update();
		jdbc.sql("DELETE FROM customer_pass WHERE customer_id = :customer").param("customer", customer).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id = :customer").param("customer", customer).update();
		jdbc.sql("DELETE FROM account WHERE id = :actor").param("actor", actor).update();
	}

	@Test
	void eligibilityUsesTheOccurrenceDateAndInclusiveSuspensionsRatherThanTodaysStatus() {
		UUID pass = pass("SCHEDULED", 8, CLASS_DAY, CLASS_DAY.plusDays(29));
		assertThat(eligibleIds(CLASS_DAY.minusDays(1))).isEmpty();
		assertThat(eligibleIds(CLASS_DAY)).containsExactly(pass);
		assertThat(eligibleIds(CLASS_DAY.plusDays(29))).containsExactly(pass);
		assertThat(eligibleIds(CLASS_DAY.plusDays(30))).isEmpty();

		jdbc.sql("UPDATE customer_pass SET status = 'ACTIVE' WHERE id = :pass").param("pass", pass).update();
		jdbc.sql("""
				INSERT INTO pass_suspension (pass_id, starts_on, ends_on, created_by)
				VALUES (:pass, :starts, :ends, :actor)
				""").param("pass", pass).param("starts", CLASS_DAY.plusDays(2))
			.param("ends", CLASS_DAY.plusDays(4)).param("actor", actor).update();
		assertThat(eligibleIds(CLASS_DAY.plusDays(1))).containsExactly(pass);
		assertThat(eligibleIds(CLASS_DAY.plusDays(2))).isEmpty();
		assertThat(eligibleIds(CLASS_DAY.plusDays(4))).isEmpty();
		assertThat(eligibleIds(CLASS_DAY.plusDays(5))).containsExactly(pass);

		jdbc.sql("UPDATE pass_suspension SET cancelled_at = now() WHERE pass_id = :pass").param("pass", pass).update();
		assertThat(eligibleIds(CLASS_DAY.plusDays(2))).containsExactly(pass);
		jdbc.sql("UPDATE pass_product SET is_active = FALSE WHERE id = :product").param("product", product).update();
		jdbc.sql("UPDATE pass_product_variant SET is_active = FALSE WHERE id = :variant").param("variant", variant).update();
		assertThat(eligibleIds(CLASS_DAY)).containsExactly(pass);
		assertThat(entitlements.eligible(customer, UUID.randomUUID(), CLASS_DAY)).isEmpty();
	}

	@Test
	void reserveRechecksPassOwnershipDatesServicesStatusAndBalance() {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID reservation = reservation();
		assertRejected(ErrorCode.NOT_FOUND,
				() -> reserve(UUID.randomUUID(), pass, service, CLASS_DAY, reservation));
		assertRejected(ErrorCode.PASS_NOT_USABLE,
				() -> reserve(customer, pass, service, CLASS_DAY.plusDays(1), reservation));
		assertRejected(ErrorCode.PASS_SERVICE_MISMATCH,
				() -> reserve(customer, pass, UUID.randomUUID(), CLASS_DAY, reservation));
		for (String status : List.of("CANCELLED", "PENDING_PAYMENT", "EXPIRED", "SUSPENDED")) {
			jdbc.sql("UPDATE customer_pass SET status = :status WHERE id = :pass")
				.param("status", status).param("pass", pass).update();
			assertRejected(ErrorCode.PASS_NOT_USABLE,
					() -> reserve(customer, pass, service, CLASS_DAY, reservation));
			assertThat(eligibleIds(CLASS_DAY)).isEmpty();
		}
		jdbc.sql("UPDATE customer_pass SET status = 'ACTIVE', valid_from = NULL WHERE id = :pass")
			.param("pass", pass).update();
		assertRejected(ErrorCode.PASS_NOT_USABLE,
				() -> reserve(customer, pass, service, CLASS_DAY, reservation));
		assertThat(eligibleIds(CLASS_DAY)).isEmpty();
		jdbc.sql("UPDATE customer_pass SET valid_from = :day, valid_until = NULL WHERE id = :pass")
			.param("day", CLASS_DAY).param("pass", pass).update();
		assertRejected(ErrorCode.PASS_NOT_USABLE,
				() -> reserve(customer, pass, service, CLASS_DAY, reservation));
		assertThat(eligibleIds(CLASS_DAY)).isEmpty();
		jdbc.sql("UPDATE customer_pass SET valid_until = :day WHERE id = :pass")
			.param("day", CLASS_DAY).param("pass", pass).update();
		jdbc.sql("UPDATE customer_pass SET status = 'ACTIVE', entries_remaining = 0 WHERE id = :pass")
			.param("pass", pass).update();
		assertRejected(ErrorCode.PASS_ENTRIES_EXHAUSTED,
				() -> reserve(customer, pass, service, CLASS_DAY, reservation));
		assertThat(eligibleIds(CLASS_DAY)).isEmpty();
		assertThat(ledgerCount(pass)).isZero();
	}

	@Test
	void aReturnedEntryAndItsLedgerAreAppliedOnlyOnceEvenIfTheOutcomeIsRetriedDifferently() {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID reservation = reservation();
		transaction.executeWithoutResult(status -> {
			reserve(customer, pass, service, CLASS_DAY, reservation);
			reserve(customer, pass, service, CLASS_DAY, reservation);
		});
		assertThat(balance(pass)).isZero();
		assertThat(ledgerCount(pass)).isEqualTo(1);
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, reservation, true, actor));
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, reservation, true, actor));
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, reservation, false, actor));
		assertThat(balance(pass)).isEqualTo(1);
		assertThat(ledgerCount(pass)).isEqualTo(2);
		assertThat(ledgerDelta(pass, "ENTRY_RESERVED")).isEqualTo(-1);
		assertThat(ledgerDelta(pass, "ENTRY_RETURNED")).isEqualTo(1);
	}

	@Test
	void forfeitureDoesNotChargeAgainAndUnlimitedPassesKeepANullBalanceWithZeroDeltaHistory() {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID reservation = reservation();
		transaction.executeWithoutResult(status -> reserve(customer, pass, service, CLASS_DAY, reservation));
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, reservation, false, actor));
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, reservation, true, actor));
		assertThat(balance(pass)).isZero();
		assertThat(ledgerDelta(pass, "ENTRY_CONSUMED")).isZero();
		assertThat(ledgerCount(pass)).isEqualTo(2);

		jdbc.sql("UPDATE pass_product SET kind = 'TIME_PASS' WHERE id = :product")
			.param("product", product).update();
		UUID unlimited = pass("ACTIVE", null, CLASS_DAY, CLASS_DAY);
		UUID unlimitedReservation = reservation();
		assertThat(eligibleIds(CLASS_DAY)).containsExactly(unlimited);
		transaction.executeWithoutResult(status -> reserve(customer, unlimited, service, CLASS_DAY, unlimitedReservation));
		transaction.executeWithoutResult(status -> entitlements.release(customer, unlimited, unlimitedReservation, true, actor));
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :pass")
			.param("pass", unlimited).query().singleRow()).containsEntry("entries_remaining", null);
		assertThat(ledgerCount(unlimited)).isEqualTo(2);
		assertThat(ledgerDelta(unlimited, "ENTRY_RESERVED")).isZero();
		assertThat(ledgerDelta(unlimited, "ENTRY_RETURNED")).isZero();
	}

	@Test
	void reservationBalanceAndLedgerRollBackTogether() {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID reservationId = UUID.randomUUID();
		UUID event = event();
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			insertReservation(reservationId, event);
			reserve(customer, pass, service, CLASS_DAY, reservationId);
			throw new IllegalStateException("Simulated later failure in the booking transaction");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(balance(pass)).isEqualTo(1);
		assertThat(ledgerCount(pass)).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE id = :id")
			.param("id", reservationId).query(Long.class).single()).isZero();
	}

	@Test
	void competingReservationsCannotSpendTheSameLastEntry() throws Exception {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID first = reservation();
		UUID second = reservation();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var left = executor.submit(() -> concurrentReserve(start, pass, first));
			var right = executor.submit(() -> concurrentReserve(start, pass, second));
			start.countDown();
			assertThat(List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("RESERVED", "PASS_ENTRIES_EXHAUSTED");
		}
		assertThat(balance(pass)).isZero();
		assertThat(ledgerCount(pass)).isEqualTo(1);
	}

	@Test
	void aPreviousEligibilityReadCannotHideAnEntrySpentByAnotherTransaction() throws Exception {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID first = reservation();
		UUID second = reservation();
		CountDownLatch read = new CountDownLatch(1);
		CountDownLatch spent = new CountDownLatch(1);
		try (var executor = Executors.newSingleThreadExecutor()) {
			var staleReader = executor.submit(() -> {
				try {
					transaction.executeWithoutResult(status -> {
						assertThat(eligibleIds(CLASS_DAY)).containsExactly(pass);
						read.countDown();
						await(spent);
						reserve(customer, pass, service, CLASS_DAY, second);
					});
					return "RESERVED";
				}
				catch (ApiException rejection) {
					return rejection.getErrorCode().name();
				}
			});
			assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
			transaction.executeWithoutResult(status -> reserve(customer, pass, service, CLASS_DAY, first));
			spent.countDown();
			assertThat(staleReader.get(15, TimeUnit.SECONDS)).isEqualTo("PASS_ENTRIES_EXHAUSTED");
		}
		assertThat(balance(pass)).isZero();
		assertThat(ledgerCount(pass)).isEqualTo(1);
	}

	@Test
	void aCorrectionLeavesRoomForOutstandingEntryReturns() {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID reservation = reservation();
		transaction.executeWithoutResult(status -> reserve(customer, pass, service, CLASS_DAY, reservation));
		assertRejected(ErrorCode.VALIDATION_FAILED,
				() -> correct(pass, Short.MAX_VALUE, version(pass)));
		assertThat(balance(pass)).isZero();
		assertThat(ledgerCount(pass)).isEqualTo(1);

		correct(pass, Short.MAX_VALUE - 1, version(pass));
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, reservation, true, actor));
		assertThat(balance(pass)).isEqualTo(Short.MAX_VALUE);
		assertThat(ledgerDelta(pass, "CORRECTION")).isEqualTo(Short.MAX_VALUE - 1);
		assertThat(ledgerCount(pass)).isEqualTo(3);
	}

	@Test
	void consumedAndReturnedAllocationsNoLongerReserveCorrectionHeadroom() {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID consumed = reservation();
		transaction.executeWithoutResult(status -> reserve(customer, pass, service, CLASS_DAY, consumed));
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, consumed, false, actor));
		correct(pass, Short.MAX_VALUE, version(pass));
		assertThat(balance(pass)).isEqualTo(Short.MAX_VALUE);

		UUID returned = reservation();
		transaction.executeWithoutResult(status -> reserve(customer, pass, service, CLASS_DAY, returned));
		transaction.executeWithoutResult(status -> entitlements.release(customer, pass, returned, true, actor));
		correct(pass, -1, version(pass));
		correct(pass, 1, version(pass));
		assertThat(balance(pass)).isEqualTo(Short.MAX_VALUE);
	}

	@Test
	void correctionBalanceAndHistoryRollBackTogether() {
		UUID pass = pass("ACTIVE", 8, CLASS_DAY, CLASS_DAY);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			correct(pass, 2, 0);
			throw new IllegalStateException("Simulated later failure");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(balance(pass)).isEqualTo(8);
		assertThat(version(pass)).isZero();
		assertThat(ledgerCount(pass)).isZero();
	}

	@Test
	void concurrentCorrectionsWithTheSameVersionApplyOnlyOnce() throws Exception {
		UUID pass = pass("ACTIVE", 8, CLASS_DAY, CLASS_DAY);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> concurrentCorrection(start, pass, 1));
			var second = executor.submit(() -> concurrentCorrection(start, pass, 1));
			start.countDown();
			assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("CORRECTED", "VERSION_CONFLICT");
		}
		assertThat(balance(pass)).isEqualTo(9);
		assertThat(version(pass)).isEqualTo(1);
		assertThat(ledgerCount(pass)).isEqualTo(1);
	}

	@Test
	void correctionAndBookingCannotSpendTheSameLastEntry() throws Exception {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID reservation = reservation();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var correction = executor.submit(() -> concurrentCorrection(start, pass, -1));
			var booking = executor.submit(() -> concurrentReserve(start, pass, reservation));
			start.countDown();
			String corrected = correction.get(15, TimeUnit.SECONDS);
			String booked = booking.get(15, TimeUnit.SECONDS);
			if (corrected.equals("CORRECTED")) {
				assertThat(booked).isEqualTo("PASS_ENTRIES_EXHAUSTED");
			}
			else {
				assertThat(corrected).isEqualTo("VERSION_CONFLICT");
				assertThat(booked).isEqualTo("RESERVED");
			}
		}
		assertThat(balance(pass)).isZero();
		assertThat(ledgerCount(pass)).isEqualTo(1);
	}

	@Test
	void aCachedPassIsRefreshedBeforeCheckingTheCorrectionVersion() throws Exception {
		UUID pass = pass("ACTIVE", 1, CLASS_DAY, CLASS_DAY);
		UUID reservation = reservation();
		CountDownLatch read = new CountDownLatch(1);
		CountDownLatch spent = new CountDownLatch(1);
		try (var executor = Executors.newSingleThreadExecutor()) {
			var staleReader = executor.submit(() -> {
				try {
					transaction.executeWithoutResult(status -> {
						assertThat(customerPasses.get(customer, pass).getVersion()).isZero();
						read.countDown();
						await(spent);
						correct(pass, 1, 0);
					});
					return "CORRECTED";
				}
				catch (ApiException rejection) {
					return rejection.getErrorCode().name();
				}
			});
			try {
				assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
				transaction.executeWithoutResult(status -> reserve(customer, pass, service, CLASS_DAY, reservation));
			}
			finally {
				spent.countDown();
			}
			assertThat(staleReader.get(15, TimeUnit.SECONDS)).isEqualTo("VERSION_CONFLICT");
		}
		assertThat(balance(pass)).isZero();
		assertThat(ledgerCount(pass)).isEqualTo(1);
	}

	private void correct(UUID pass, int delta, int version) {
		customerPasses.correctEntries(customer, pass, new EntryCorrection(delta, null, version), actor);
	}

	private String concurrentCorrection(CountDownLatch start, UUID pass, int delta) {
		await(start);
		try {
			correct(pass, delta, 0);
			return "CORRECTED";
		}
		catch (ApiException rejection) {
			return rejection.getErrorCode().name();
		}
	}

	private int version(UUID pass) {
		return jdbc.sql("SELECT version FROM customer_pass WHERE id = :pass")
			.param("pass", pass).query(Integer.class).single();
	}

	private String concurrentReserve(CountDownLatch start, UUID pass, UUID reservation) {
		await(start);
		try {
			transaction.executeWithoutResult(status -> reserve(customer, pass, service, CLASS_DAY, reservation));
			return "RESERVED";
		}
		catch (ApiException rejection) {
			return rejection.getErrorCode().name();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for the competing transaction");
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}

	private void assertRejected(ErrorCode expected, Runnable command) {
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> command.run()))
			.isInstanceOfSatisfying(ApiException.class, rejection -> assertThat(rejection.getErrorCode()).isEqualTo(expected));
	}

	private void reserve(UUID participant, UUID pass, UUID serviceId, LocalDate day, UUID reservation) {
		entitlements.reserve(participant, pass, serviceId, day, reservation, actor);
	}

	private List<UUID> eligibleIds(LocalDate day) {
		return entitlements.eligible(customer, service, day).stream().map(BookingEntitlements.EligiblePass::id).toList();
	}

	private int balance(UUID pass) {
		return jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :pass")
			.param("pass", pass).query(Integer.class).single();
	}

	private long ledgerCount(UUID pass) {
		return jdbc.sql("SELECT count(*) FROM pass_ledger WHERE pass_id = :pass")
			.param("pass", pass).query(Long.class).single();
	}

	private int ledgerDelta(UUID pass, String kind) {
		return jdbc.sql("SELECT entries_delta FROM pass_ledger WHERE pass_id = :pass AND kind = :kind")
			.param("pass", pass).param("kind", kind).query(Integer.class).single();
	}

	private UUID pass(String status, Integer remaining, LocalDate from, LocalDate until) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				    price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'Group pass', :entries, :entries, 200, :status, :from, :until) RETURNING id
				""").param("customer", customer).param("variant", variant)
			.param("entries", remaining, java.sql.Types.SMALLINT).param("status", status)
			.param("from", from).param("until", until).query(UUID.class).single();
	}

	private UUID event() {
		OffsetDateTime starts = CLASS_DAY.atTime(18, 0).atOffset(java.time.ZoneOffset.UTC);
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, starts_at, ends_at, capacity)
				VALUES ('CLASS', :service, :starts, :ends, 10) RETURNING id
				""").param("service", service).param("starts", starts).param("ends", starts.plusHours(1))
			.query(UUID.class).single();
	}

	private UUID reservation() {
		UUID id = UUID.randomUUID();
		insertReservation(id, event());
		return id;
	}

	private void insertReservation(UUID id, UUID event) {
		jdbc.sql("""
				INSERT INTO reservation (id, event_id, customer_id, booked_by)
				VALUES (:id, :event, :customer, :actor)
				""").param("id", id).param("event", event).param("customer", customer).param("actor", actor).update();
	}

}
