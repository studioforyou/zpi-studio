package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.BookingEntitlements;

/** Real owner queries and HTTP authorization, with isolated fixtures rolled back after each test. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional(isolation = Isolation.REPEATABLE_READ)
class TraineeOverviewTests {

	private static final String BASE = "/api/v1/me/trainees";

	private static final String TRAINER = "overview-trainer-token";

	private static final String OTHER = "overview-other-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private BookingEntitlements entitlements;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private Instant now;

	private LocalDate today;

	private UUID trainerAccount;

	private UUID trainer;

	private UUID otherTrainer;

	private UUID trainee;

	private UUID service;

	private UUID product;

	private UUID finiteVariant;

	private UUID unlimitedVariant;

	@BeforeEach
	void setUp() {
		now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
		trainerAccount = account("TRAINER", TRAINER);
		trainer = staff(trainerAccount);
		otherTrainer = staff(account("TRAINER", OTHER));
		trainee = customer("Tomasz");
		assign(trainee, trainer, "PERSONAL_TRAINING", now.minus(10, ChronoUnit.DAYS), null);
		service = service("PERSONAL_TRAINING", true);
		qualify(trainer, service);
		qualify(otherTrainer, service);
		product = product(service);
		finiteVariant = variant(product, 8);
		unlimitedVariant = variant(product, null);
	}

	@Test
	void listsOnlyQualifiedPersonalPackagesAndKeepsTheirIndividualBalancesAndValidity() throws Exception {
		UUID finite = pass(trainee, finiteVariant, 8, 3, "ACTIVE", today.minusDays(10), today.plusDays(20));
		UUID unlimited = pass(trainee, unlimitedVariant, null, null, "ACTIVE", today.minusDays(1), today.plusDays(30));
		UUID groupService = service("GROUP_CLASS", true);
		qualify(trainer, groupService);
		UUID groupPass = pass(trainee, variant(product(groupService), null), null, null, "ACTIVE", today, today.plusDays(30));
		UUID unqualifiedService = service("PERSONAL_TRAINING", true);
		UUID hiddenPass = pass(trainee, variant(product(unqualifiedService), 8), 8, 8, "ACTIVE", today, today.plusDays(30));
		UUID inactiveService = service("PERSONAL_TRAINING", false);
		qualify(trainer, inactiveService);
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", product).param("service", inactiveService).update();

		String body = detail(trainee).andExpect(status().isOk())
			.andExpect(jsonPath("$.availability").value("AVAILABLE"))
			.andExpect(jsonPath("$.checkedOn").value(today.toString()))
			.andExpect(jsonPath("$.checkedAt").isNotEmpty())
			.andExpect(jsonPath("$.passes[*].id").value(containsInAnyOrder(finite.toString(), unlimited.toString())))
			.andReturn().getResponse().getContentAsString();
		assertThat(passView(body, finite)).containsEntry("entriesRemaining", 3).containsEntry("lowBalance", true)
			.containsEntry("eligible", true).containsEntry("validUntil", today.plusDays(20).toString())
			.containsEntry("serviceTypeIds", List.of(service.toString()));
		assertThat(passView(body, unlimited)).containsEntry("entriesRemaining", null).containsEntry("lowBalance", false);
		assertThat(body).doesNotContain(groupPass.toString(), hiddenPass.toString(), "pricePaid", "phone", "health");
		perform(TRAINER, BASE + "/overview").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].customerId").value(trainee.toString()))
			.andExpect(jsonPath("$.items[0].passes.length()").value(2));
	}

	@Test
	void availabilityMatchesExistingBookingEntitlementsAcrossDatesBalancesAndSuspensions() throws Exception {
		UUID boundary = pass(trainee, finiteVariant, 8, 1, "SCHEDULED", today, today);
		UUID zero = pass(trainee, finiteVariant, 8, 0, "ACTIVE", today, today.plusDays(10));
		UUID future = pass(trainee, finiteVariant, 8, 8, "SCHEDULED", today.plusDays(1), today.plusDays(30));
		UUID expired = pass(trainee, finiteVariant, 8, 8, "ACTIVE", today.minusDays(20), today.minusDays(1));
		UUID pending = pass(trainee, finiteVariant, 8, 8, "PENDING_PAYMENT", today, today.plusDays(30));
		UUID cancelled = pass(trainee, finiteVariant, 8, 8, "CANCELLED", today, today.plusDays(30));
		UUID suspended = pass(trainee, finiteVariant, 8, 8, "ACTIVE", today, today.plusDays(30));
		UUID cancelledSuspension = pass(trainee, finiteVariant, 8, 4, "ACTIVE", today, today.plusDays(30));
		UUID futureSuspension = pass(trainee, finiteVariant, 8, 3, "ACTIVE", today, today.plusDays(30));
		UUID unlimited = pass(trainee, unlimitedVariant, null, null, "ACTIVE", today, today.plusDays(30));
		suspend(suspended, today, today.plusDays(2), false);
		suspend(cancelledSuspension, today, today.plusDays(2), true);
		suspend(futureSuspension, today.plusDays(1), today.plusDays(2), false);
		Set<String> canonical = entitlements.eligible(trainee, service, today).stream()
			.map(item -> item.id().toString()).collect(Collectors.toSet());
		String body = detail(trainee).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> views = JsonPath.read(body, "$.passes");
		assertThat(views.stream().filter(view -> Boolean.TRUE.equals(view.get("eligible")))
			.map(view -> (String) view.get("id")).collect(Collectors.toSet())).isEqualTo(canonical)
			.containsExactlyInAnyOrder(boundary.toString(), cancelledSuspension.toString(), futureSuspension.toString(), unlimited.toString());
		for (UUID unavailable : List.of(zero, future, expired, pending, cancelled, suspended)) {
			assertThat(passView(body, unavailable)).containsEntry("eligible", false).containsEntry("lowBalance", false);
		}
		assertThat(passView(body, boundary)).containsEntry("status", "ACTIVE").containsEntry("lowBalance", true);
		assertThat(passView(body, future)).containsEntry("status", "SCHEDULED");
		assertThat(passView(body, expired)).containsEntry("status", "EXPIRED");
		assertThat(passView(body, suspended)).containsEntry("status", "SUSPENDED");
		assertThat(passView(body, cancelledSuspension)).containsEntry("lowBalance", false);
		assertThat(passView(body, futureSuspension)).containsEntry("lowBalance", true);
	}

	@Test
	void alreadyAllocatedZeroBalanceFundsTheNextSessionButNotAnotherNewTraining() throws Exception {
		UUID empty = pass(trainee, finiteVariant, 8, 0, "ACTIVE", today.minusDays(10), today.plusDays(30));
		UUID next = event(trainer, "PERSONAL_TRAINING", now.plus(2, ChronoUnit.DAYS), "SCHEDULED");
		UUID nextBooking = reserve(next, trainee, empty, "OPEN", false, false);
		ledger(empty, nextBooking, "ENTRY_RESERVED", -1);
		UUID past = event(trainer, "PERSONAL_TRAINING", now.minus(2, ChronoUnit.DAYS), "COMPLETED");
		UUID pastBooking = reserve(past, trainee, empty, "FINAL", true, true);
		ledger(empty, pastBooking, "ENTRY_CONSUMED", -1);

		detail(trainee).andExpect(status().isOk())
			.andExpect(jsonPath("$.availability").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.primaryPassId").value(empty.toString()))
			.andExpect(jsonPath("$.nextTraining.id").value(next.toString()))
			.andExpect(jsonPath("$.nextTraining.funding").value("PASS_RESERVED"))
			.andExpect(jsonPath("$.nextTraining.requiresAttention").value(false))
			.andExpect(jsonPath("$.attentionTraining").value(nullValue()));
		calendar(trainee, now.minus(3, ChronoUnit.DAYS), now.plus(3, ChronoUnit.DAYS))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].funding").value("PASS_CONSUMED"))
			.andExpect(jsonPath("$.items[0].requiresAttention").value(false));
	}

	@Test
	void distinguishesRealAccountingFromMissingOrContradictoryEntriesAndDisputes() throws Exception {
		UUID finite = pass(trainee, finiteVariant, 8, 2, "ACTIVE", today.minusDays(10), today.plusDays(30));
		UUID unlimited = pass(trainee, unlimitedVariant, null, null, "ACTIVE", today.minusDays(10), today.plusDays(30));
		Map<UUID, String> expected = new LinkedHashMap<>();
		UUID modern = pastBooking(finite, "FINAL");
		ledger(finite, modern, "ENTRY_RESERVED", -1);
		ledger(finite, modern, "ENTRY_CONSUMED", 0);
		expected.put(modern, "PASS_CONSUMED");
		UUID legacy = pastBooking(finite, "FINAL");
		ledger(finite, legacy, "ENTRY_CONSUMED", -1);
		expected.put(legacy, "PASS_CONSUMED");
		UUID reserved = pastBooking(finite, "OPEN");
		ledger(finite, reserved, "ENTRY_RESERVED", -1);
		expected.put(reserved, "PASS_RESERVED");
		UUID returned = pastBooking(finite, "FINAL");
		ledger(finite, returned, "ENTRY_RESERVED", -1);
		ledger(finite, returned, "ENTRY_RETURNED", 1);
		expected.put(returned, "PASS_RETURNED");
		expected.put(pastBooking(finite, "FINAL"), "INCONSISTENT");
		UUID doubleSpend = pastBooking(finite, "FINAL");
		ledger(finite, doubleSpend, "ENTRY_RESERVED", -1);
		ledger(finite, doubleSpend, "ENTRY_CONSUMED", -1);
		expected.put(doubleSpend, "INCONSISTENT");
		UUID contradictory = pastBooking(finite, "FINAL");
		ledger(finite, contradictory, "ENTRY_RESERVED", -1);
		ledger(finite, contradictory, "ENTRY_RETURNED", 1);
		ledger(finite, contradictory, "ENTRY_CONSUMED", 0);
		expected.put(contradictory, "INCONSISTENT");
		UUID foreignLedger = pastBooking(finite, "FINAL");
		ledger(unlimited, foreignLedger, "ENTRY_CONSUMED", 0);
		expected.put(foreignLedger, "INCONSISTENT");
		UUID otherCustomer = customer("Anna");
		UUID foreignPass = pass(otherCustomer, finiteVariant, 8, 8, "ACTIVE", today, today.plusDays(20));
		UUID wrongOwner = pastBooking(foreignPass, "FINAL");
		ledger(foreignPass, wrongOwner, "ENTRY_CONSUMED", -1);
		expected.put(wrongOwner, "INCONSISTENT");
		UUID unlimitedUse = pastBooking(unlimited, "FINAL");
		ledger(unlimited, unlimitedUse, "ENTRY_RESERVED", 0);
		ledger(unlimited, unlimitedUse, "ENTRY_CONSUMED", 0);
		expected.put(unlimitedUse, "PASS_CONSUMED");
		UUID outsidePackage = pastBooking(null, "FINAL");
		expected.put(outsidePackage, "NO_PASS");
		UUID disputed = pastBooking(finite, "DISPUTED");
		ledger(finite, disputed, "ENTRY_CONSUMED", -1);
		expected.put(disputed, "PASS_CONSUMED");
		UUID openDispute = pastBooking(finite, "FINAL");
		ledger(finite, openDispute, "ENTRY_CONSUMED", -1);
		jdbc.sql("INSERT INTO training_dispute (reservation_id, raised_by, reason) VALUES (:id, :actor, 'Wyjaśnienie')")
			.param("id", openDispute).param("actor", trainerAccount).update();
		expected.put(openDispute, "PASS_CONSUMED");

		String body = calendar(trainee, now.minus(4, ChronoUnit.DAYS), now).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		for (Map.Entry<UUID, String> entry : expected.entrySet()) {
			assertThat(sessionView(body, entry.getKey())).containsEntry("funding", entry.getValue());
		}
		assertThat(sessionView(body, modern)).containsEntry("requiresAttention", false);
		assertThat(sessionView(body, legacy)).containsEntry("requiresAttention", false);
		assertThat(sessionView(body, outsidePackage)).containsEntry("requiresAttention", false);
		assertThat(sessionView(body, disputed)).containsEntry("settlementStatus", "DISPUTED").containsEntry("requiresAttention", true);
		assertThat(sessionView(body, openDispute)).containsEntry("settlementStatus", "DISPUTED").containsEntry("requiresAttention", true);
		assertThat(sessionView(body, doubleSpend)).containsEntry("requiresAttention", true);
	}

	@Test
	void makeUpFundingRequiresMatchingCustomerReservationSourceAndUsageMarkers() throws Exception {
		UUID source = pass(trainee, finiteVariant, 8, 0, "EXPIRED", today.minusDays(60), today.minusDays(1));
		UUID used = pastBooking(null, "FINAL");
		UUID credit = credit(trainee, source, used);
		linkCredit(used, credit);
		UUID missingMarker = pastBooking(null, "FINAL");
		UUID incomplete = credit(trainee, source, missingMarker);
		linkCredit(missingMarker, incomplete);
		jdbc.sql("UPDATE makeup_credit SET used_at = NULL WHERE id = :id").param("id", incomplete).update();
		UUID bothSources = pastBooking(source, "FINAL");
		linkCredit(bothSources, credit(trainee, source, bothSources));
		UUID otherCustomer = customer("Inny uczestnik");
		UUID foreignSource = pass(otherCustomer, finiteVariant, 8, 0, "EXPIRED", today.minusDays(60), today.minusDays(1));
		UUID foreign = pastBooking(null, "FINAL");
		linkCredit(foreign, credit(trainee, foreignSource, foreign));
		UUID wrongReservation = pastBooking(null, "FINAL");
		linkCredit(wrongReservation, credit(trainee, source, null));

		String body = calendar(trainee, now.minus(4, ChronoUnit.DAYS), now).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(sessionView(body, used)).containsEntry("funding", "MAKEUP_USED")
			.containsEntry("passId", source.toString()).containsEntry("requiresAttention", false);
		for (UUID invalid : List.of(missingMarker, bothSources, foreign, wrongReservation)) {
			assertThat(sessionView(body, invalid)).containsEntry("funding", "INCONSISTENT").containsEntry("requiresAttention", true);
		}
	}

	@Test
	void calendarUsesOverlapBoundsAndExcludesOtherTrainersGroupsAndOtherCustomers() throws Exception {
		Instant from = now.minus(1, ChronoUnit.DAYS);
		Instant to = now.plus(1, ChronoUnit.DAYS);
		UUID overlap = event(trainer, "PERSONAL_TRAINING", from.minus(30, ChronoUnit.MINUTES), "COMPLETED");
		UUID overlapBooking = reserve(overlap, trainee, null, "FINAL", true, true);
		UUID inside = event(trainer, "PERSONAL_TRAINING", now, "SCHEDULED");
		UUID cancelled = reserve(inside, trainee, null, "OPEN", false, false);
		jdbc.sql("UPDATE reservation SET status = 'CANCELLED', created_at = created_at - INTERVAL '1 day' WHERE id = :id")
			.param("id", cancelled).update();
		UUID current = reserve(inside, trainee, null, "OPEN", false, false);
		for (UUID hidden : List.of(event(trainer, "PERSONAL_TRAINING", from.minus(1, ChronoUnit.HOURS), "COMPLETED"),
				event(trainer, "PERSONAL_TRAINING", to, "SCHEDULED"),
				event(otherTrainer, "PERSONAL_TRAINING", now, "SCHEDULED"),
				event(trainer, "CLASS", now, "SCHEDULED"))) {
			reserve(hidden, trainee, null, "OPEN", false, false);
		}
		UUID otherCustomer = customer("Drugi");
		reserve(event(trainer, "PERSONAL_TRAINING", now, "SCHEDULED"), otherCustomer, null, "OPEN", false, false);

		calendar(trainee, from, to).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(overlap.toString(), inside.toString())))
			.andExpect(jsonPath("$.items[*].reservationId").value(containsInAnyOrder(overlapBooking.toString(), current.toString())))
			.andExpect(jsonPath("$.items[0].kind").value("PERSONAL_TRAINING"))
			.andExpect(jsonPath("$.items[0].staff[0].id").doesNotExist())
			.andExpect(jsonPath("$.items[0].resources").isArray());
		calendar(trainee, to, from).andExpect(status().isBadRequest());
		calendar(trainee, now, now.plus(94, ChronoUnit.DAYS)).andExpect(status().isBadRequest());
	}

	@Test
	void sharedCalendarKeepsBothTraineesOfOneEventWithTheirOwnNamesAndFunding() throws Exception {
		UUID second = customer("Monika");
		assign(second, trainer, "PERSONAL_TRAINING", now.minus(1, ChronoUnit.DAYS), null);
		UUID firstPass = pass(trainee, finiteVariant, 8, 2, "ACTIVE", today, today.plusDays(30));
		UUID secondPass = pass(second, unlimitedVariant, null, null, "ACTIVE", today, today.plusDays(30));
		Instant from = now.minus(1, ChronoUnit.DAYS);
		Instant to = now.plus(1, ChronoUnit.DAYS);
		UUID overlap = reserve(event(trainer, "PERSONAL_TRAINING", from.minus(30, ChronoUnit.MINUTES), "COMPLETED"),
				trainee, null, "FINAL", true, true);
		UUID sharedEvent = event(trainer, "PERSONAL_TRAINING", now, "SCHEDULED");
		UUID first = reserve(sharedEvent, trainee, firstPass, "OPEN", false, false);
		UUID next = reserve(sharedEvent, second, secondPass, "OPEN", false, false);
		ledger(firstPass, first, "ENTRY_RESERVED", -1);
		ledger(secondPass, next, "ENTRY_RESERVED", 0);
		UUID simultaneousEvent = event(trainer, "PERSONAL_TRAINING", now, "SCHEDULED");
		UUID simultaneous = reserve(simultaneousEvent, second, null, "OPEN", false, false);
		UUID later = reserve(event(trainer, "PERSONAL_TRAINING", now.plus(2, ChronoUnit.HOURS), "SCHEDULED"),
				second, null, "OPEN", false, false);

		String body = sharedCalendar(TRAINER, from, to).andExpect(status().isOk())
			.andExpect(jsonPath("$.checkedOn").value(today.toString()))
			.andExpect(jsonPath("$.checkedAt").isString())
			.andExpect(jsonPath("$.items.length()").value(5))
			.andReturn().getResponse().getContentAsString();
		assertThat(sessionView(body, first)).containsEntry("id", sharedEvent.toString())
			.containsEntry("customerId", trainee.toString()).containsEntry("customerName", "Tomasz Testowy")
			.containsEntry("passId", firstPass.toString()).containsEntry("funding", "PASS_RESERVED");
		assertThat(sessionView(body, next)).containsEntry("id", sharedEvent.toString())
			.containsEntry("customerId", second.toString()).containsEntry("customerName", "Monika Testowy")
			.containsEntry("passId", secondPass.toString()).containsEntry("funding", "PASS_RESERVED");
		List<String> bookingIds = JsonPath.read(body, "$.items[*].reservationId");
		List<String> expectedOrder = new ArrayList<>(List.of(overlap.toString()));
		List<String> sharedOrder = List.of(
				(trainee.compareTo(second) < 0 ? first : next).toString(),
				(trainee.compareTo(second) < 0 ? next : first).toString());
		if (sharedEvent.compareTo(simultaneousEvent) < 0) {
			expectedOrder.addAll(sharedOrder);
			expectedOrder.add(simultaneous.toString());
		}
		else {
			expectedOrder.add(simultaneous.toString());
			expectedOrder.addAll(sharedOrder);
		}
		expectedOrder.add(later.toString());
		assertThat(bookingIds).containsExactlyElementsOf(expectedOrder);
		calendar(trainee, from, to).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].customerId").value(trainee.toString()))
			.andExpect(jsonPath("$.items[0].customerName").value("Tomasz Testowy"));

		jdbc.sql("UPDATE reservation SET customer_pass_id = :otherPass WHERE id = :id")
			.param("otherPass", firstPass).param("id", next).update();
		String inconsistent = sharedCalendar(TRAINER, from, to).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(sessionView(inconsistent, next)).containsEntry("customerId", second.toString())
			.containsEntry("funding", "INCONSISTENT").containsEntry("passId", null).containsEntry("passName", null);
		assertThat(sessionView(inconsistent, first)).containsEntry("passId", firstPass.toString())
			.containsEntry("funding", "PASS_RESERVED");
	}

	@Test
	void sharedCalendarExcludesOtherStaffGroupsAndNonCurrentOrInactiveTrainees() throws Exception {
		Instant from = now.minus(1, ChronoUnit.DAYS);
		Instant to = now.plus(1, ChronoUnit.DAYS);
		UUID visible = reserve(event(trainer, "PERSONAL_TRAINING", now, "SCHEDULED"), trainee, null, "OPEN", false, false);
		reserve(event(otherTrainer, "PERSONAL_TRAINING", now, "SCHEDULED"), trainee, null, "OPEN", false, false);
		reserve(event(trainer, "CLASS", now, "SCHEDULED"), trainee, null, "OPEN", false, false);
		reserve(event(trainer, "PERSONAL_TRAINING", from.minus(1, ChronoUnit.HOURS), "COMPLETED"),
				trainee, null, "FINAL", true, true);
		reserve(event(trainer, "PERSONAL_TRAINING", to, "SCHEDULED"), trainee, null, "OPEN", false, false);
		UUID unrelated = customer("Nieprzypisany");
		UUID ended = customer("Zakończony");
		UUID future = customer("Przyszły");
		UUID groupOnly = customer("Grupowy");
		UUID inactive = customer("Nieaktywny");
		UUID otherStaff = customer("Innego trenera");
		assign(ended, trainer, "PERSONAL_TRAINING", now.minus(10, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		assign(future, trainer, "PERSONAL_TRAINING", now.plus(1, ChronoUnit.DAYS), null);
		assign(groupOnly, trainer, "GROUP_CLASS", now.minus(1, ChronoUnit.DAYS), null);
		assign(inactive, trainer, "PERSONAL_TRAINING", now.minus(1, ChronoUnit.DAYS), null);
		assign(otherStaff, otherTrainer, "PERSONAL_TRAINING", now.minus(1, ChronoUnit.DAYS), null);
		jdbc.sql("UPDATE customer_profile SET status = 'ARCHIVED' WHERE id = :id").param("id", inactive).update();
		for (UUID hidden : List.of(unrelated, ended, future, groupOnly, inactive, otherStaff)) {
			reserve(event(trainer, "PERSONAL_TRAINING", now, "SCHEDULED"), hidden, null, "OPEN", false, false);
		}
		String body = sharedCalendar(TRAINER, from, to).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].reservationId").value(visible.toString()))
			.andExpect(jsonPath("$.items[0].customerId").value(trainee.toString()))
			.andReturn().getResponse().getContentAsString();
		for (UUID hidden : List.of(unrelated, ended, future, groupOnly, inactive, otherStaff)) {
			assertThat(body).doesNotContain(hidden.toString());
		}
	}

	@Test
	void sharedCalendarValidatesWindowEvenWithoutTrainees() throws Exception {
		sharedCalendar(OTHER, now, now.plus(93, ChronoUnit.DAYS)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").value(empty()));
		sharedCalendar(OTHER, now, now).andExpect(status().isBadRequest());
		sharedCalendar(OTHER, now.plus(1, ChronoUnit.DAYS), now).andExpect(status().isBadRequest());
		sharedCalendar(OTHER, now, now.plus(93, ChronoUnit.DAYS).plusSeconds(1)).andExpect(status().isBadRequest());
	}

	@Test
	void activeRelationshipsDoNotExposeEndedFutureOrGroupOnlyAssignments() throws Exception {
		UUID ended = customer("Zakończony");
		UUID future = customer("Przyszły");
		UUID groupOnly = customer("Grupowy");
		assign(ended, trainer, "PERSONAL_TRAINING", now.minus(10, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		assign(future, trainer, "PERSONAL_TRAINING", now.plus(1, ChronoUnit.DAYS), null);
		assign(groupOnly, trainer, "GROUP_CLASS", now.minus(1, ChronoUnit.DAYS), null);
		for (UUID hidden : List.of(ended, future, groupOnly, UUID.randomUUID())) {
			detail(hidden).andExpect(status().isNotFound());
			calendar(hidden, now, now.plus(1, ChronoUnit.DAYS)).andExpect(status().isNotFound());
		}
		perform(TRAINER, BASE + "/overview").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].customerId").value(containsInAnyOrder(trainee.toString())));
		perform(OTHER, BASE + "/" + trainee + "/overview").andExpect(status().isNotFound());
		perform(OTHER, BASE + "/overview").andExpect(status().isOk()).andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void inactiveProfilesAndMissingQualificationsHaveDistinctEmptyStates() throws Exception {
		pass(trainee, finiteVariant, 8, 8, "ACTIVE", today, today.plusDays(10));
		jdbc.sql("DELETE FROM staff_qualification WHERE staff_id = :id").param("id", trainer).update();
		detail(trainee).andExpect(status().isOk())
			.andExpect(jsonPath("$.availability").value("NO_QUALIFIED_SERVICES"))
			.andExpect(jsonPath("$.passes").value(empty()));
		jdbc.sql("UPDATE customer_profile SET status = 'ARCHIVED' WHERE id = :id").param("id", trainee).update();
		perform(TRAINER, BASE + "/overview").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].isActive").value(false))
			.andExpect(jsonPath("$.items[0].availability").value("INACTIVE"))
			.andExpect(jsonPath("$.items[0].passes").value(empty()))
			.andExpect(jsonPath("$.items[0].nextTraining").value(nullValue()));
		detail(trainee).andExpect(status().isNotFound());
		jdbc.sql("UPDATE staff_profile SET status = 'ARCHIVED' WHERE id = :id").param("id", trainer).update();
		perform(TRAINER, BASE + "/overview").andExpect(status().isNotFound());
	}

	@Test
	void listReadsEveryOwnTraineeWithoutReusingAnotherParticipantsPackages() throws Exception {
		UUID firstPass = pass(trainee, finiteVariant, 8, 2, "ACTIVE", today, today.plusDays(10));
		UUID second = customer("Monika");
		assign(second, trainer, "PERSONAL_TRAINING", now.minus(1, ChronoUnit.DAYS), null);
		UUID secondPass = pass(second, unlimitedVariant, null, null, "ACTIVE", today, today.plusDays(30));
		UUID unrelated = customer("Obcy");
		pass(unrelated, finiteVariant, 8, 8, "ACTIVE", today, today.plusDays(30));
		String body = perform(TRAINER, BASE + "/overview").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].customerId").value(containsInAnyOrder(trainee.toString(), second.toString())))
			.andReturn().getResponse().getContentAsString();
		List<String> first = JsonPath.read(body, "$.items[?(@.customerId == '" + trainee + "')].passes[*].id");
		List<String> next = JsonPath.read(body, "$.items[?(@.customerId == '" + second + "')].passes[*].id");
		assertThat(first).containsExactly(firstPass.toString());
		assertThat(next).containsExactly(secondPass.toString());
		assertThat(body).doesNotContain(unrelated.toString());
	}

	@Test
	void onlyActiveTrainerAccountsCanReadTheEndpoints() throws Exception {
		for (String role : List.of("ADMIN", "CLIENT")) {
			String token = "overview-" + role;
			account(role, token);
			perform(token, BASE + "/overview").andExpect(status().isForbidden());
			perform(token, BASE + "/" + trainee + "/overview").andExpect(status().isForbidden());
			sharedCalendar(token, now, now.plus(1, ChronoUnit.DAYS)).andExpect(status().isForbidden());
		}
		mvc.perform(get(BASE + "/overview")).andExpect(status().isUnauthorized());
		mvc.perform(get(BASE + "/personal-trainings").param("from", now.toString())
			.param("to", now.plus(1, ChronoUnit.DAYS).toString())).andExpect(status().isUnauthorized());
		String noProfile = "overview-no-profile";
		account("TRAINER", noProfile);
		perform(noProfile, BASE + "/overview").andExpect(status().isNotFound());
		sharedCalendar(noProfile, now, now.plus(1, ChronoUnit.DAYS)).andExpect(status().isNotFound());
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :id").param("id", trainerAccount).update();
		perform(TRAINER, BASE + "/overview").andExpect(status().isForbidden());
		sharedCalendar(TRAINER, now, now.plus(1, ChronoUnit.DAYS)).andExpect(status().isForbidden());
	}

	@Test
	void repeatedReadsDoNotSpendEntriesConfirmSessionsOrChangeAccounting() throws Exception {
		UUID ownPass = pass(trainee, finiteVariant, 8, 2, "ACTIVE", today.minusDays(10), today.plusDays(30));
		UUID booking = pastBooking(ownPass, "OPEN");
		ledger(ownPass, booking, "ENTRY_RESERVED", -1);
		Map<String, String> before = fingerprints();
		for (int i = 0; i < 2; i++) {
			perform(TRAINER, BASE + "/overview").andExpect(status().isOk());
			detail(trainee).andExpect(status().isOk());
			calendar(trainee, now.minus(4, ChronoUnit.DAYS), now.plus(4, ChronoUnit.DAYS)).andExpect(status().isOk());
			sharedCalendar(TRAINER, now.minus(4, ChronoUnit.DAYS), now.plus(4, ChronoUnit.DAYS)).andExpect(status().isOk());
		}
		assertThat(fingerprints()).isEqualTo(before);
	}

	private ResultActions detail(UUID customerId) throws Exception {
		return perform(TRAINER, BASE + "/" + customerId + "/overview");
	}

	private ResultActions perform(String token, String path) throws Exception {
		entityManager.clear();
		return mvc.perform(get(path).header("Authorization", "Bearer " + token));
	}

	private ResultActions calendar(UUID customerId, Instant from, Instant to) throws Exception {
		entityManager.clear();
		return mvc.perform(get(BASE + "/" + customerId + "/personal-trainings")
			.header("Authorization", "Bearer " + TRAINER).param("from", from.toString()).param("to", to.toString()));
	}

	private ResultActions sharedCalendar(String token, Instant from, Instant to) throws Exception {
		entityManager.clear();
		return mvc.perform(get(BASE + "/personal-trainings").header("Authorization", "Bearer " + token)
			.param("from", from.toString()).param("to", to.toString()));
	}

	private static Map<String, Object> passView(String body, UUID passId) {
		List<Map<String, Object>> found = JsonPath.read(body, "$.passes[?(@.id == '" + passId + "')]");
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private static Map<String, Object> sessionView(String body, UUID reservationId) {
		List<Map<String, Object>> found = JsonPath.read(body, "$.items[?(@.reservationId == '" + reservationId + "')]");
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private UUID account(String role, String token) {
		UUID id = UUID.randomUUID();
		String uid = "overview-" + id;
		jdbc.sql("INSERT INTO account (id, email, status) VALUES (:id, :email, 'ACTIVE')")
			.param("id", id).param("email", id + "@trainee-overview.test").update();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id).param("role", role).update();
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID staff(UUID account) {
		return jdbc.sql("INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:id, 'Trener', 'Testowy') RETURNING id")
			.param("id", account).query(UUID.class).single();
	}

	private UUID customer(String name) {
		return jdbc.sql("INSERT INTO customer_profile (kind, first_name, last_name) VALUES ('ADULT', :name, 'Testowy') RETURNING id")
			.param("name", name).query(UUID.class).single();
	}

	private void assign(UUID customer, UUID staff, String scope, Instant start, Instant end) {
		jdbc.sql("INSERT INTO staff_assignment (customer_id, staff_id, scope, assigned_by, started_at, ended_at) VALUES (:customer, :staff, :scope, :actor, :start, :end)")
			.param("customer", customer).param("staff", staff).param("scope", scope).param("actor", trainerAccount)
			.param("start", utc(start)).param("end", end == null ? null : utc(end), Types.TIMESTAMP_WITH_TIMEZONE).update();
	}

	private UUID service(String category, boolean active) {
		return jdbc.sql("INSERT INTO service_type (name, category, default_duration_minutes, is_active) VALUES (:name, :category, 60, :active) RETURNING id")
			.param("name", "Trening " + UUID.randomUUID()).param("category", category).param("active", active).query(UUID.class).single();
	}

	private void qualify(UUID staff, UUID serviceId) {
		jdbc.sql("INSERT INTO staff_qualification (staff_id, service_type_id) VALUES (:staff, :service)")
			.param("staff", staff).param("service", serviceId).update();
	}

	private UUID product(UUID serviceId) {
		UUID id = jdbc.sql("INSERT INTO pass_product (name, kind, audience) VALUES ('Pakiet testowy', 'PT_PACKAGE', 'ADULT') RETURNING id")
			.query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:id, :service)")
			.param("id", id).param("service", serviceId).update();
		return id;
	}

	private UUID variant(UUID productId, Integer entries) {
		return jdbc.sql("INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price) VALUES (:product, :entries, 60, 500.00) RETURNING id")
			.param("product", productId).param("entries", entries, Types.SMALLINT).query(UUID.class).single();
	}

	private UUID pass(UUID customer, UUID variant, Integer total, Integer remaining, String state, LocalDate from, LocalDate to) {
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				  price_paid, status, valid_from, valid_until, created_by)
				VALUES (:customer, :variant, 'Pakiet PT', :total, :remaining, 500.00, :state, :from, :to, :actor) RETURNING id
				""").param("customer", customer).param("variant", variant).param("total", total, Types.SMALLINT)
			.param("remaining", remaining, Types.SMALLINT).param("state", state).param("from", from)
			.param("to", to).param("actor", trainerAccount).query(UUID.class).single();
	}

	private void suspend(UUID passId, LocalDate from, LocalDate to, boolean cancelled) {
		jdbc.sql("INSERT INTO pass_suspension (pass_id, starts_on, ends_on, created_by, cancelled_at) VALUES (:pass, :from, :to, :actor, :cancelled)")
			.param("pass", passId).param("from", from).param("to", to).param("actor", trainerAccount)
			.param("cancelled", cancelled ? utc(now) : null, Types.TIMESTAMP_WITH_TIMEZONE).update();
	}

	private UUID event(UUID staff, String kind, Instant start, String state) {
		UUID id = jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility, status)
				VALUES (:kind, :service, 'Trening testowy', :start, :end, 1, 'PRIVATE', :state) RETURNING id
				""").param("kind", kind).param("service", service).param("start", utc(start))
			.param("end", utc(start.plus(1, ChronoUnit.HOURS))).param("state", state).query(UUID.class).single();
		jdbc.sql("INSERT INTO event_staff (event_id, staff_id, role) VALUES (:event, :staff, 'LEAD')")
			.param("event", id).param("staff", staff).update();
		return id;
	}

	private UUID reserve(UUID eventId, UUID customer, UUID pass, String settlement, boolean trainerConfirmed, boolean clientConfirmed) {
		return jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, customer_pass_id, booked_by, source, attendance,
				  trainer_confirmed_at, client_confirmed_at, settlement_status)
				SELECT id, :customer, :pass, :actor, 'STAFF', :attendance,
				  CASE WHEN :trainer THEN ends_at END, CASE WHEN :client THEN ends_at + INTERVAL '1 minute' END,
				  :settlement FROM schedule_event WHERE id = :event RETURNING id
				""").param("customer", customer).param("pass", pass, Types.OTHER).param("actor", trainerAccount)
			.param("attendance", trainerConfirmed ? "PRESENT" : "UNMARKED").param("trainer", trainerConfirmed)
			.param("client", clientConfirmed).param("settlement", settlement).param("event", eventId).query(UUID.class).single();
	}

	private UUID pastBooking(UUID pass, String settlement) {
		return reserve(event(trainer, "PERSONAL_TRAINING", now.minus(2, ChronoUnit.DAYS), "COMPLETED"),
				trainee, pass, settlement, true, true);
	}

	private void ledger(UUID passId, UUID booking, String kind, int delta) {
		jdbc.sql("INSERT INTO pass_ledger (pass_id, reservation_id, kind, entries_delta, actor_account_id) VALUES (:pass, :booking, :kind, :delta, :actor)")
			.param("pass", passId).param("booking", booking).param("kind", kind).param("delta", delta)
			.param("actor", trainerAccount).update();
	}

	private UUID credit(UUID customer, UUID source, UUID booking) {
		return jdbc.sql("""
				INSERT INTO makeup_credit (customer_id, source_pass_id, status, granted_at, expires_at, used_reservation_id, used_at)
				VALUES (:customer, :source, 'USED', :granted, :expiry, :booking, :used) RETURNING id
				""").param("customer", customer).param("source", source).param("granted", utc(now.minus(10, ChronoUnit.DAYS)))
			.param("expiry", utc(now.minus(1, ChronoUnit.DAYS))).param("booking", booking, Types.OTHER)
			.param("used", utc(now.minus(2, ChronoUnit.DAYS))).query(UUID.class).single();
	}

	private void linkCredit(UUID booking, UUID creditId) {
		jdbc.sql("UPDATE reservation SET makeup_credit_id = :credit WHERE id = :id").param("credit", creditId).param("id", booking).update();
	}

	private Map<String, String> fingerprints() {
		Map<String, String> snapshots = new LinkedHashMap<>();
		for (String table : List.of("customer_pass", "pass_ledger", "pass_suspension", "reservation", "makeup_credit", "training_dispute", "staff_assignment")) {
			snapshots.put(table, jdbc.sql("SELECT md5(COALESCE(string_agg(row_to_json(t)::TEXT, ',' ORDER BY id), '')) FROM " + table + " t")
				.query(String.class).single());
		}
		return snapshots;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
