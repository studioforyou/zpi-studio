package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancelRequest;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/** Real owner operations and PostgreSQL transactions, including competing last-place bookings. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupClassBookingTests {

	private static final String PARENT_TOKEN = "booking-parent-token";

	private static final String OTHER_TOKEN = "booking-other-token";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoSpyBean
	private BookingEntitlements entitlements;

	@MockitoSpyBean
	private ReservationStore reservations;

	@Autowired
	private GroupClassBookingService bookings;

	@Autowired
	private JdbcClient jdbc;

	private UUID actor;
	private UUID profile;
	private UUID otherActor;
	private UUID otherProfile;
	private UUID service;
	private UUID product;
	private UUID variant;
	private UUID event;
	private UUID pass;
	private UUID otherPass;
	private Instant startsAt;

	@BeforeEach
	void setUp() {
		actor = account("booking-parent");
		otherActor = account("booking-other");
		signIn(actor, PARENT_TOKEN);
		signIn(otherActor, OTHER_TOKEN);
		profile = customer(actor, "ADULT");
		otherProfile = customer(otherActor, "ADULT");
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, max_participants)
				VALUES ('booking-test-group', 'GROUP', 60, 12) RETURNING id
				""").query(UUID.class).single();
		product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('booking-test-pass', 'ENTRY_PASS', 'ANY') RETURNING id
				""").query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 120) RETURNING id
				""").param("product", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", product).param("service", service).update();
		pass = pass(profile);
		otherPass = pass(otherProfile);
		startsAt = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);
		event = event(1, "CLASS", "PUBLIC");
	}

	@AfterEach
	void cleanUp() {
		// Scope every cleanup to this fixture. Other integration fixtures retain their data.
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :variant)")
			.param("variant", variant).update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :service)")
			.param("service", service).update();
		jdbc.sql("DELETE FROM booking_policy WHERE service_type_id = :service OR event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :service)")
			.param("service", service).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM customer_pass WHERE variant_id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM guardianship WHERE guardian_id = :profile").param("profile", profile).update();
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (:actors) OR first_name = 'booking-test-child'")
			.param("actors", List.of(actor, otherActor)).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (:actors)").param("actors", List.of(actor, otherActor)).update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (:actors)").param("actors", List.of(actor, otherActor)).update();
		jdbc.sql("DELETE FROM account WHERE id IN (:actors)").param("actors", List.of(actor, otherActor)).update();
	}

	@Test
	void serviceColorsComeFromTheOfferAndParticipantNamesRequireAnActiveBooking() throws Exception {
		jdbc.sql("UPDATE service_type SET calendar_color = '#238B67' WHERE id = :id").param("id", service).update();
		assertThat(bookings.get(actor, profile, event).calendarColor()).isEqualTo("#238B67");
		assertThat(bookings.list(actor, profile, startsAt.minusSeconds(1), startsAt.plusSeconds(3601), false).getFirst().calendarColor())
			.isEqualTo("#238B67");
		String path = base(profile) + "/group-classes/" + event + "/participants";
		mvc.perform(get(path).header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		jdbc.sql("UPDATE schedule_event SET capacity = 3 WHERE id = :id").param("id", event).update();
		var own = bookings.book(actor, profile, event, pass).reservation();
		var other = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		// Both participants deliberately have the same display name: keep both rows and one self marker.
		mvc.perform(get(path).header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].isSelf").value(true))
			.andExpect(jsonPath("$.items[1].isSelf").value(false))
			.andExpect(jsonPath("$.items[0].displayName").value("booking-test-adult Participant"))
			.andExpect(jsonPath("$.items[0].id").doesNotExist())
			.andExpect(jsonPath("$.items[0].customerId").doesNotExist())
			.andExpect(jsonPath("$.items[0].email").doesNotExist())
			.andExpect(jsonPath("$.items[0].phone").doesNotExist());
		mvc.perform(get(path).header("Authorization", "Bearer " + OTHER_TOKEN))
			.andExpect(status().isNotFound());
		bookings.cancel(otherActor, otherProfile, other.id(), new CancelRequest(0, true));
		assertThat(bookings.participants(actor, profile, event)).hasSize(1);
		bookings.cancel(actor, profile, own.id(), new CancelRequest(0, true));
		mvc.perform(get(path).header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isForbidden());
	}

	@Test
	void aCancellationCommittedBeforeTheRosterReadPreventsDisclosureOfOtherParticipants() throws Exception {
		jdbc.sql("UPDATE schedule_event SET capacity = 3 WHERE id = :id").param("id", event).update();
		var own = bookings.book(actor, profile, event, pass).reservation();
		bookings.book(otherActor, otherProfile, event, otherPass);
		try (var executor = Executors.newSingleThreadExecutor()) {
			org.mockito.stubbing.Answer<Object> cancelBeforeRosterRead = invocation -> {
				executor.submit(() -> bookings.cancel(actor, profile, own.id(), new CancelRequest(0, true)))
					.get(10, TimeUnit.SECONDS);
				return invocation.callRealMethod();
			};
			// Exercise both the guarded query and the former separate roster read: the old
			// implementation has already checked access when this cancellation commits.
			doAnswer(cancelBeforeRosterRead).when(reservations).activeParticipantIdsForBookedProfile(profile, event);
			doAnswer(cancelBeforeRosterRead).when(reservations).activeParticipantIds(event);
			assertError(() -> bookings.participants(actor, profile, event), ErrorCode.ACCESS_DENIED);
		}
		assertThat(bookings.getReservation(actor, profile, own.id()).status()).isEqualTo("CANCELLED");
		assertThat(bookings.participants(otherActor, otherProfile, event)).hasSize(1);
	}

	@Test
	void aGuardianCanSeeTheBookedChildsClassmatesUntilGuardianshipEnds() {
		UUID child = customer(null, "CHILD");
		jdbc.sql("INSERT INTO guardianship(guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", profile).param("child", child).update();
		bookings.book(actor, child, event, pass(child));
		var participants = bookings.participants(actor, child, event);
		assertThat(participants).hasSize(1);
		assertThat(participants.getFirst().isSelf()).isTrue();
		assertThat(participants.getFirst().displayName()).isEqualTo("booking-test-child Participant");
		jdbc.sql("UPDATE guardianship SET ended_at = now() WHERE child_id = :child").param("child", child).update();
		assertError(() -> bookings.participants(actor, child, event), ErrorCode.NOT_FOUND);
	}

	@Test
	void httpOfferHasTheClientProjectionAndAuthenticationProtectsProfileData() throws Exception {
		String window = "?from=" + startsAt.minusSeconds(1) + "&to=" + startsAt.plusSeconds(7200);
		mvc.perform(get(base(profile) + "/group-classes" + window)
			.header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(event.toString()))
			.andExpect(jsonPath("$.items[0].startsAt").value(startsAt.toString()))
			.andExpect(jsonPath("$.items[0].availablePlaces").value(1))
			.andExpect(jsonPath("$.items[0].staff").isArray())
			.andExpect(jsonPath("$.items[0].resources").isArray())
			.andExpect(jsonPath("$.items[0].booking.allowed").value(true))
			.andExpect(jsonPath("$.items[0].booking.eligiblePasses[0].id").value(pass.toString()))
			.andExpect(jsonPath("$.items[0].participants").doesNotExist())
			.andExpect(jsonPath("$.items[0].guestName").doesNotExist());
		mvc.perform(get(base(profile) + "/group-classes/" + event)).andExpect(status().isUnauthorized());
		mvc.perform(get(base(profile) + "/group-classes/" + event)
			.header("Authorization", "Bearer " + OTHER_TOKEN))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :actor").param("actor", actor).update();
		mvc.perform(get(base(profile) + "/group-classes/" + event)
			.header("Authorization", "Bearer " + PARENT_TOKEN)).andExpect(status().isForbidden());
	}

	@Test
	void httpBookingAndCancellationReturnTheirLocationVersionAndActualConsequence() throws Exception {
		String createPath = base(profile) + "/group-classes/" + event + "/reservations";
		var result = mvc.perform(post(createPath).header("Authorization", "Bearer " + PARENT_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"customerPassId\":\"" + pass + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.eventId").value(event.toString()))
			.andExpect(jsonPath("$.customerId").value(profile.toString()))
			.andExpect(jsonPath("$.customerPassId").value(pass.toString()))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.version").value(0))
			.andExpect(jsonPath("$.cancellation.allowed").value(true))
			.andExpect(jsonPath("$.cancellation.entryReturned").value(true))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		String reservationId = JsonPath.read(body, "$.id");
		String location = base(profile) + "/reservations/" + reservationId;
		assertThat(result.getResponse().getHeader("Location")).isEqualTo(location);
		mvc.perform(post(createPath).header("Authorization", "Bearer " + PARENT_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"customerPassId\":\"" + pass + "\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(reservationId));
		mvc.perform(get(location).header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(reservationId));
		mvc.perform(post(location + "/cancel").header("Authorization", "Bearer " + PARENT_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"expectedEntryReturned\":true}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.entryReturned").value(true));
		assertThat(balance(pass)).isEqualTo(8);
	}

	@Test
	void httpMutationValidationRejectsMissingPassVersionOrConfirmedConsequence() throws Exception {
		String path = base(profile) + "/group-classes/" + event + "/reservations";
		mvc.perform(post(path).header("Authorization", "Bearer " + PARENT_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		var created = bookings.book(actor, profile, event, pass).reservation();
		String cancelPath = base(profile) + "/reservations/" + created.id() + "/cancel";
		for (String invalid : List.of("{}", "{\"expectedVersion\":0}", "{\"expectedVersion\":-1,\"expectedEntryReturned\":true}")) {
			mvc.perform(post(cancelPath).header("Authorization", "Bearer " + PARENT_TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		mvc.perform(post(cancelPath).header("Authorization", "Bearer " + OTHER_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"expectedEntryReturned\":true}"))
			.andExpect(status().isNotFound());
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(bookings.getReservation(actor, profile, created.id()).status()).isEqualTo("ACTIVE");
	}

	@Test
	void bookingAndRetryAllocateOnePlaceAndOneEntry() {
		var created = bookings.book(actor, profile, event, pass);
		var retry = bookings.book(actor, profile, event, pass);

		assertThat(created.created()).isTrue();
		assertThat(retry.created()).isFalse();
		assertThat(retry.reservation().id()).isEqualTo(created.reservation().id());
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(reservations()).isEqualTo(1);
		assertThat(ledger("ENTRY_RESERVED")).isEqualTo(1);
		var offer = bookings.get(actor, profile, event);
		assertThat(offer.availablePlaces()).isZero();
		assertThat(offer.activeReservations()).isEqualTo(1);
		assertThat(offer.ownReservation().id()).isEqualTo(created.reservation().id());
		assertThat(offer.booking().reason()).isEqualTo("ALREADY_RESERVED");
	}

	@Test
	void invalidChosenPassRollsBackTheReservationWithoutChangingEitherBalance() {
		assertError(() -> bookings.book(actor, profile, event, otherPass), ErrorCode.NOT_FOUND);
		assertThat(reservations()).isZero();
		assertThat(balance(pass)).isEqualTo(8);
		assertThat(balance(otherPass)).isEqualTo(8);
		assertThat(ledger("ENTRY_RESERVED")).isZero();
	}

	@Test
	void aNonexistentChosenPassReturnsNotFoundAndRollsBackTheWholeBooking() throws Exception {
		UUID nonexistentPass = UUID.randomUUID();
		assertError(() -> bookings.book(actor, profile, event, nonexistentPass), ErrorCode.NOT_FOUND);
		mvc.perform(post(base(profile) + "/group-classes/" + event + "/reservations")
			.header("Authorization", "Bearer " + PARENT_TOKEN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"customerPassId\":\"" + nonexistentPass + "\"}"))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		assertThat(reservations()).isZero();
		assertThat(balance(pass)).isEqualTo(8);
		assertThat(balance(otherPass)).isEqualTo(8);
		assertThat(ledger("ENTRY_RESERVED")).isZero();
	}

	@Test
	void concurrentDifferentClassesSharingTheLastPassEntryDoNotDeadlockOrSpendItTwice() throws Exception {
		UUID secondEvent = event(1, "CLASS", "PUBLIC");
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 1 WHERE id = :pass")
			.param("pass", pass).update();
		CountDownLatch beforeAllocation = new CountDownLatch(2);
		// Both coordinators must finish inserting their reservation before either pass lock.
		// This deterministically exercises the FK KEY SHARE -> entitlement lock interaction.
		BookingEntitlements target = AopTestUtils.getUltimateTargetObject(entitlements);
		doAnswer(invocation -> {
			beforeAllocation.countDown();
			if (!beforeAllocation.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("The competing reservation never reached pass allocation.");
			}
			return invocation.callRealMethod();
		}).when(target).reserve(eq(profile), eq(pass), eq(service), any(LocalDate.class), any(UUID.class), eq(actor));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> bookingOutcome(event));
			var second = executor.submit(() -> bookingOutcome(secondEvent));
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("CREATED", "PASS_ENTRIES_EXHAUSTED");
		}
		assertThat(jdbc.sql("SELECT count(*) FROM reservation WHERE event_id IN (:events) AND status = 'ACTIVE'")
			.param("events", List.of(event, secondEvent)).query(Integer.class).single()).isEqualTo(1);
		assertThat(balance(pass)).isZero();
		assertThat(ledger("ENTRY_RESERVED")).isEqualTo(1);
	}

	@Test
	void concurrentCustomersCannotBothTakeTheLastPlace() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(competingBooking(actor, profile, pass, ready, start));
			var second = executor.submit(competingBooking(otherActor, otherProfile, otherPass, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("CREATED", "CLASS_FULL");
		}
		assertThat(reservations()).isEqualTo(1);
		assertThat(balance(pass) + balance(otherPass)).isEqualTo(15);
		assertThat(ledger("ENTRY_RESERVED")).isEqualTo(1);
	}

	@Test
	void concurrentRetriesOfOneProfileDoNotSpendTwice() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(competingBooking(actor, profile, pass, ready, start));
			var second = executor.submit(competingBooking(actor, profile, pass, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("CREATED", "EXISTING");
		}
		assertThat(reservations()).isEqualTo(1);
		assertThat(balance(pass)).isEqualTo(7);
	}

	@Test
	void timelyCancellationReturnsTheEntryOnceAndAllowsBookingAgain() {
		var created = bookings.book(actor, profile, event, pass).reservation();
		var cancelled = bookings.cancel(actor, profile, created.id(), new CancelRequest(0, true));
		var retry = bookings.cancel(actor, profile, created.id(), new CancelRequest(0, true));
		assertThat(cancelled.status()).isEqualTo("CANCELLED");
		assertThat(cancelled.cancelKind()).isEqualTo("IN_TIME");
		assertThat(cancelled.entryReturned()).isTrue();
		assertThat(cancelled.version()).isEqualTo(1);
		assertThat(retry).isEqualTo(cancelled);
		assertThat(balance(pass)).isEqualTo(8);
		assertThat(ledger("ENTRY_RETURNED")).isEqualTo(1);
		assertThat(bookings.get(actor, profile, event).availablePlaces()).isEqualTo(1);
		assertThat(bookings.book(actor, profile, event, pass).reservation().id()).isNotEqualTo(created.id());
		assertThat(balance(pass)).isEqualTo(7);
	}

	@Test
	void laterPolicyChangesDoNotChangeTheReservationsCancellationTerms() {
		var created = bookings.book(actor, profile, event, pass).reservation();
		jdbc.sql("""
				INSERT INTO booking_policy (scope, service_type_id, cancellation_allowed,
				free_cancel_hours, late_cancel_allowed, late_cancel_forfeits_entry)
				VALUES ('SERVICE_TYPE', :service, false, 100, false, true)
				""").param("service", service).update();
		assertThat(bookings.getReservation(actor, profile, created.id()).cancellation().entryReturned()).isTrue();
		assertThat(bookings.cancel(actor, profile, created.id(), new CancelRequest(0, true)).entryReturned()).isTrue();
		assertThat(balance(pass)).isEqualTo(8);
	}

	@Test
	void changedDeadlineConsequenceRequiresAnotherConfirmationAndLateCancellationForfeitsOnce() {
		var created = bookings.book(actor, profile, event, pass).reservation();
		// Represents time crossing the stored deadline between viewing and confirming.
		jdbc.sql("UPDATE reservation SET free_cancel_until = :past WHERE id = :id")
			.param("past", utc(Instant.now().minusSeconds(30))).param("id", created.id()).update();
		assertError(() -> bookings.cancel(actor, profile, created.id(), new CancelRequest(0, true)),
				ErrorCode.CANCELLATION_CONSEQUENCE_CHANGED);
		assertThat(bookings.getReservation(actor, profile, created.id()).status()).isEqualTo("ACTIVE");
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(ledger("ENTRY_CONSUMED")).isZero();
		var cancelled = bookings.cancel(actor, profile, created.id(), new CancelRequest(0, false));
		assertThat(cancelled.entryReturned()).isFalse();
		assertThat(cancelled.cancelKind()).isEqualTo("LATE");
		bookings.cancel(actor, profile, created.id(), new CancelRequest(0, false));
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(ledger("ENTRY_CONSUMED")).isEqualTo(1);
	}

	@Test
	void staleVersionAndUnknownLegacyTermsCannotCancelOrReturnAnEntry() {
		var created = bookings.book(actor, profile, event, pass).reservation();
		assertError(() -> bookings.cancel(actor, profile, created.id(), new CancelRequest(4, true)),
				ErrorCode.VERSION_CONFLICT);
		jdbc.sql("UPDATE reservation SET late_cancel_allowed_snapshot = null WHERE id = :id")
			.param("id", created.id()).update();
		assertError(() -> bookings.cancel(actor, profile, created.id(), new CancelRequest(0, true)),
				ErrorCode.CANCELLATION_TERMS_UNAVAILABLE);
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(bookings.getReservation(actor, profile, created.id()).status()).isEqualTo("ACTIVE");
	}

	@Test
	void aGuardianActsForAChildButAnotherAccountCannotReadOrChangeTheirBooking() {
		UUID child = customer(null, "CHILD");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", profile).param("child", child).update();
		UUID childPass = pass(child);
		var created = bookings.book(actor, child, event, childPass).reservation();
		assertThat(jdbc.sql("SELECT source FROM reservation WHERE id = :id").param("id", created.id())
			.query(String.class).single()).isEqualTo("GUARDIAN");
		assertError(() -> bookings.get(otherActor, child, event), ErrorCode.NOT_FOUND);
		assertError(() -> bookings.cancel(otherActor, child, created.id(), new CancelRequest(0, true)), ErrorCode.NOT_FOUND);
		jdbc.sql("UPDATE guardianship SET ended_at = now() WHERE child_id = :child").param("child", child).update();
		assertError(() -> bookings.getReservation(actor, child, created.id()), ErrorCode.NOT_FOUND);
		assertThat(balance(childPass)).isEqualTo(7);
	}

	@Test
	void privateAndNonClassEventsAreNotExposedAndWindowIsBounded() {
		UUID privateEvent = event(3, "CLASS", "PRIVATE");
		UUID personal = event(3, "PERSONAL_TRAINING", "PUBLIC");
		assertError(() -> bookings.get(actor, profile, privateEvent), ErrorCode.NOT_FOUND);
		assertError(() -> bookings.book(actor, profile, personal, pass), ErrorCode.NOT_FOUND);
		assertThat(bookings.list(actor, profile, startsAt.minusSeconds(1), startsAt.plusSeconds(7200), false))
			.extracting(GroupClassDtos.GroupClassResponse::id).containsExactly(event);
		assertError(() -> bookings.list(actor, profile, startsAt, startsAt.plus(Duration.ofDays(94)), false),
				ErrorCode.VALIDATION_FAILED);
	}

	private Callable<String> competingBooking(UUID customerActor, UUID customerProfile, UUID customerPass,
			CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Concurrent booking start timed out.");
			}
			try {
				return bookings.book(customerActor, customerProfile, event, customerPass).created() ? "CREATED" : "EXISTING";
			}
			catch (ApiException ex) {
				return ex.getErrorCode().name();
			}
		};
	}

	private String bookingOutcome(UUID eventId) {
		try {
			return bookings.book(actor, profile, eventId, pass).created() ? "CREATED" : "EXISTING";
		}
		catch (ApiException exception) {
			return exception.getErrorCode().name();
		}
	}

	private static void assertError(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}

	private UUID account(String name) {
		return jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@booking.test").query(UUID.class).single();
	}

	private void signIn(UUID accountId, String token) {
		String uid = "booking-" + accountId;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:actor, 'FIREBASE', :uid)")
			.param("actor", accountId).param("uid", uid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :actor, id FROM role WHERE code = 'CLIENT'")
			.param("actor", accountId).update();
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
	}

	private static String base(UUID profileId) {
		return "/api/v1/me/profiles/" + profileId;
	}

	private UUID customer(UUID account, String kind) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, birth_date, account_id)
				VALUES (:kind, :firstName, 'Participant', :birthDate, :account) RETURNING id
				""").param("kind", kind).param("firstName", account == null ? "booking-test-child" : "booking-test-adult")
			.param("birthDate", LocalDate.now().minusYears("CHILD".equals(kind) ? 10 : 30))
			.param("account", account, java.sql.Types.OTHER).query(UUID.class).single();
	}

	private UUID pass(UUID customer) {
		LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total,
				entries_remaining, price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'booking-test-pass', 8, 8, 120, 'ACTIVE', :from, :until) RETURNING id
				""").param("customer", customer).param("variant", variant).param("from", today)
			.param("until", today.plusDays(29)).query(UUID.class).single();
	}

	private UUID event(int capacity, String kind, String visibility) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility)
				VALUES (:kind, :service, 'booking-test-class', :startsAt, :endsAt, :capacity, :visibility) RETURNING id
				""").param("kind", kind).param("service", service).param("startsAt", utc(startsAt))
			.param("endsAt", utc(startsAt.plusSeconds(3600))).param("capacity", capacity)
			.param("visibility", visibility).query(UUID.class).single();
	}

	private int balance(UUID passId) {
		return jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id").param("id", passId)
			.query(Integer.class).single();
	}

	private int reservations() {
		return jdbc.sql("SELECT count(*) FROM reservation WHERE event_id = :event AND status = 'ACTIVE'")
			.param("event", event).query(Integer.class).single();
	}

	private int ledger(String kind) {
		return jdbc.sql("""
				SELECT count(*) FROM pass_ledger
				WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :variant) AND kind = :kind
				""").param("variant", variant).param("kind", kind).query(Integer.class).single();
	}

	private static OffsetDateTime utc(Instant time) {
		return time.atOffset(ZoneOffset.UTC);
	}

}
