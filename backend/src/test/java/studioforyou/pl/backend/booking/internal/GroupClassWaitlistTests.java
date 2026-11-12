package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
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
import java.util.Map;
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

import studioforyou.pl.backend.PassNotificationCleanup;
import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancelRequest;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.GroupClassResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ReservationResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.WaitlistEntryResponse;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * The waiting list of a full class and the automatic promotion on real owner operations and
 * PostgreSQL transactions, including cancellations that promote each other's participants.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupClassWaitlistTests {

	private static final String PARENT_TOKEN = "waitlist-parent-token";

	private static final String OTHER_TOKEN = "waitlist-other-token";

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
	private GroupClassWaitlistService waitlists;

	@Autowired
	private WaitlistOfferExpiry offerExpiry;

	@Autowired
	private JdbcClient jdbc;

	private UUID actor;
	private UUID profile;
	private UUID otherActor;
	private UUID otherProfile;
	private UUID thirdActor;
	private UUID thirdProfile;
	private UUID service;
	private UUID product;
	private UUID variant;
	private UUID event;
	private UUID pass;
	private UUID otherPass;
	private UUID thirdPass;
	private Instant startsAt;

	@BeforeEach
	void setUp() {
		actor = account("waitlist-parent");
		otherActor = account("waitlist-other");
		thirdActor = account("waitlist-third");
		signIn(actor, PARENT_TOKEN);
		signIn(otherActor, OTHER_TOKEN);
		profile = customer(actor, "ADULT");
		otherProfile = customer(otherActor, "ADULT");
		thirdProfile = customer(thirdActor, "ADULT");
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, max_participants)
				VALUES ('waitlist-test-group', 'GROUP', 60, 12) RETURNING id
				""").query(UUID.class).single();
		product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('waitlist-test-pass', 'ENTRY_PASS', 'ANY') RETURNING id
				""").query(UUID.class).single();
		variant = jdbc.sql("""
				INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price)
				VALUES (:product, 8, 30, 120) RETURNING id
				""").param("product", product).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (:product, :service)")
			.param("product", product).param("service", service).update();
		pass = pass(profile);
		otherPass = pass(otherProfile);
		thirdPass = pass(thirdProfile);
		startsAt = Instant.now().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.SECONDS);
		event = event(1);
	}

	@AfterEach
	void cleanUp() {
		studioforyou.pl.backend.WaitlistNotificationCleanup.removeForService(jdbc, service);
		// Scope every cleanup to this fixture. Other integration fixtures retain their data.
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :variant)")
			.param("variant", variant).update();
		jdbc.sql("DELETE FROM waitlist_entry WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :service)")
			.param("service", service).update();
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
		List<UUID> actors = List.of(actor, otherActor, thirdActor);
		PassNotificationCleanup.deleteNotifications(jdbc, actors);
		jdbc.sql("DELETE FROM guardianship WHERE guardian_id = :profile").param("profile", profile).update();
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (:actors) OR first_name = 'waitlist-test-child'")
			.param("actors", actors).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (:actors)").param("actors", actors).update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (:actors)").param("actors", actors).update();
		jdbc.sql("DELETE FROM account WHERE id IN (:actors)").param("actors", actors).update();
	}

	@Test
	void aFullClassOffersTheWaitingListAndJoiningChargesNothingAndKeepsThePlaceOnRetry() {
		assertThat(bookings.get(actor, profile, event).waitlist().reason()).isEqualTo("CLASS_NOT_FULL");
		bookings.book(otherActor, otherProfile, event, otherPass);
		GroupClassResponse full = bookings.get(actor, profile, event);
		assertThat(full.booking().reason()).isEqualTo("CLASS_FULL");
		assertThat(full.waitlist().allowed()).isTrue();
		assertThat(full.waitlist().waitingCount()).isZero();
		assertThat(full.waitlist().ownEntry()).isNull();

		var joined = waitlists.join(actor, profile, event, pass);
		var retry = waitlists.join(actor, profile, event, pass);

		assertThat(joined.created()).isTrue();
		assertThat(joined.entry().status()).isEqualTo("WAITING");
		assertThat(joined.entry().position()).isEqualTo(1);
		assertThat(joined.entry().customerPassId()).isEqualTo(pass);
		assertThat(joined.entry().promotionBlockedReason()).isNull();
		assertThat(retry.created()).isFalse();
		assertThat(retry.entry().id()).isEqualTo(joined.entry().id());
		assertThat(balance(pass)).isEqualTo(8);
		var waiting = bookings.get(actor, profile, event).waitlist();
		assertThat(waiting.allowed()).isFalse();
		assertThat(waiting.reason()).isEqualTo("WAITLIST_ALREADY_JOINED");
		assertThat(waiting.waitingCount()).isEqualTo(1);
		assertThat(waiting.ownEntry().id()).isEqualTo(joined.entry().id());
		assertThat(waiting.ownEntry().position()).isEqualTo(1);
		var listed = bookings.list(actor, profile, startsAt.minusSeconds(1), startsAt.plusSeconds(3601), false);
		assertThat(listed.getFirst().waitlist().ownEntry().position()).isEqualTo(1);
		assertThat(bookings.get(otherActor, otherProfile, event).waitlist().reason()).isEqualTo("ALREADY_RESERVED");
	}

	@Test
	void joiningIsRefusedForTheSameReasonsAsBooking() {
		assertError(() -> waitlists.join(actor, profile, event, pass), ErrorCode.CLASS_NOT_FULL);
		bookings.book(otherActor, otherProfile, event, otherPass);
		assertError(() -> waitlists.join(otherActor, otherProfile, event, otherPass), ErrorCode.ALREADY_RESERVED);
		assertError(() -> waitlists.join(actor, profile, event, otherPass), ErrorCode.PASS_NOT_USABLE);
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 0 WHERE id = :pass").param("pass", pass).update();
		assertError(() -> waitlists.join(actor, profile, event, pass), ErrorCode.NO_ELIGIBLE_PASS);
		assertThat(bookings.get(actor, profile, event).waitlist().reason()).isEqualTo("NO_ELIGIBLE_PASS");
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 8 WHERE id = :pass").param("pass", pass).update();
		jdbc.sql("INSERT INTO booking_policy (scope, event_id, waitlist_enabled) VALUES ('EVENT', :event, false)")
			.param("event", event).update();
		assertError(() -> waitlists.join(actor, profile, event, pass), ErrorCode.WAITLIST_DISABLED);
		assertThat(bookings.get(actor, profile, event).waitlist().reason()).isEqualTo("WAITLIST_DISABLED");
		jdbc.sql("DELETE FROM booking_policy WHERE event_id = :event").param("event", event).update();
		startsIn(event, Duration.ofMinutes(20));
		assertError(() -> waitlists.join(actor, profile, event, pass), ErrorCode.BOOKING_CLOSED);
		assertThat(waitlistRows()).isZero();
		assertError(() -> waitlists.join(otherActor, profile, event, otherPass), ErrorCode.NOT_FOUND);
	}

	@Test
	void aCancellationGivesTheFreedPlaceToTheFirstWaitingParticipantInTheSameTransaction() {
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse first = waitlists.join(actor, profile, event, pass).entry();
		WaitlistEntryResponse second = waitlists.join(thirdActor, thirdProfile, event, thirdPass).entry();
		assertThat(second.position()).isEqualTo(2);

		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		GroupClassResponse promoted = bookings.get(actor, profile, event);
		assertThat(promoted.ownReservation().status()).isEqualTo("ACTIVE");
		assertThat(promoted.ownReservation().customerPassId()).isEqualTo(pass);
		assertThat(promoted.ownReservation().cancellation().allowed()).isTrue();
		assertThat(promoted.ownReservation().cancellation().entryReturned()).isTrue();
		assertThat(promoted.availablePlaces()).isZero();
		assertThat(promoted.waitlist().ownEntry()).isNull();
		assertThat(promoted.waitlist().waitingCount()).isEqualTo(1);
		UUID reservationId = promoted.ownReservation().id();
		var row = jdbc.sql("SELECT source, booked_by FROM reservation WHERE id = :id").param("id", reservationId)
			.query().singleRow();
		assertThat(row).containsEntry("source", "WAITLIST").containsEntry("booked_by", actor);
		assertThat(entry(first.id())).containsEntry("status", "PROMOTED").containsEntry("reservation_id", reservationId);
		assertThat(entry(first.id()).get("resolved_at")).isNotNull();
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(balance(otherPass)).isEqualTo(8);
		assertThat(jdbc.sql("""
				SELECT actor_account_id FROM pass_ledger WHERE reservation_id = :id AND kind = 'ENTRY_RESERVED'
				""").param("id", reservationId).query(UUID.class).single()).isEqualTo(actor);
		assertThat(waitlists.get(thirdActor, thirdProfile, second.id()).position()).isEqualTo(1);
		assertThat(activeReservations(event)).isEqualTo(1);
	}

	@Test
	void aWaitingParticipantWhoCannotBookNowIsSkippedAndKeepsTheirPlace() {
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse first = waitlists.join(actor, profile, event, pass).entry();
		WaitlistEntryResponse second = waitlists.join(thirdActor, thirdProfile, event, thirdPass).entry();
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 0 WHERE id = :pass").param("pass", pass).update();
		WaitlistEntryResponse blocked = bookings.get(actor, profile, event).waitlist().ownEntry();
		assertThat(blocked.promotionBlockedReason()).isEqualTo("NO_ELIGIBLE_PASS");

		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		assertThat(entry(first.id())).containsEntry("status", "WAITING");
		assertThat(entry(second.id())).containsEntry("status", "PROMOTED");
		assertThat(bookings.get(thirdActor, thirdProfile, event).ownReservation().customerPassId()).isEqualTo(thirdPass);
		WaitlistEntryResponse kept = waitlists.get(actor, profile, first.id());
		assertThat(kept.position()).isEqualTo(1);
		assertThat(kept.promotionBlockedReason()).isEqualTo("NO_ELIGIBLE_PASS");
		assertThat(balance(pass)).isZero();
		assertThat(balance(thirdPass)).isEqualTo(7);
	}

	@Test
	void aPromotionChargesAnotherEligiblePassWhenTheChosenOneCanNoLongerBeUsed() {
		UUID secondPass = pass(profile);
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		waitlists.join(actor, profile, event, pass);
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 0 WHERE id = :pass").param("pass", pass).update();

		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		assertThat(bookings.get(actor, profile, event).ownReservation().customerPassId()).isEqualTo(secondPass);
		assertThat(balance(secondPass)).isEqualTo(7);
		assertThat(balance(pass)).isZero();
	}

	@Test
	void noPlaceIsGivenFromTheListOnceBookingHasClosed() {
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse waiting = waitlists.join(actor, profile, event, pass).entry();
		startsIn(event, Duration.ofMinutes(20));
		// The reservation keeps its free cancellation deadline, so the cancellation still returns the entry.
		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		assertThat(entry(waiting.id())).containsEntry("status", "WAITING");
		GroupClassResponse closed = bookings.get(actor, profile, event);
		assertThat(closed.ownReservation()).isNull();
		assertThat(closed.availablePlaces()).isEqualTo(1);
		assertThat(closed.waitlist().ownEntry().promotionBlockedReason()).isEqualTo("BOOKING_CLOSED");
		assertThat(balance(pass)).isEqualTo(8);
	}

	@Test
	void leavingIsIdempotentMovesTheOthersUpAndIsRefusedAfterAPromotion() {
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse first = waitlists.join(actor, profile, event, pass).entry();
		WaitlistEntryResponse second = waitlists.join(thirdActor, thirdProfile, event, thirdPass).entry();

		WaitlistEntryResponse left = waitlists.leave(actor, profile, first.id());
		WaitlistEntryResponse again = waitlists.leave(actor, profile, first.id());

		assertThat(left.status()).isEqualTo("CANCELLED");
		assertThat(left.position()).isNull();
		assertThat(left.resolvedAt()).isNotNull();
		assertThat(again).isEqualTo(left);
		assertThat(waitlists.get(thirdActor, thirdProfile, second.id()).position()).isEqualTo(1);
		assertThat(bookings.get(actor, profile, event).waitlist().allowed()).isTrue();
		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));
		assertThat(bookings.get(thirdActor, thirdProfile, event).ownReservation()).isNotNull();
		assertError(() -> waitlists.leave(thirdActor, thirdProfile, second.id()), ErrorCode.WAITLIST_ENTRY_NOT_WAITING);
		assertError(() -> waitlists.leave(otherActor, thirdProfile, second.id()), ErrorCode.NOT_FOUND);
		assertError(() -> waitlists.leave(actor, profile, second.id()), ErrorCode.NOT_FOUND);
	}

	@Test
	void bookingAFreePlaceWhileWaitingTakesTheParticipantOffTheList() {
		bookings.book(otherActor, otherProfile, event, otherPass);
		WaitlistEntryResponse waiting = waitlists.join(actor, profile, event, pass).entry();
		// A place the queue could not use earlier, e.g. a larger room.
		jdbc.sql("UPDATE schedule_event SET capacity = 2 WHERE id = :id").param("id", event).update();
		assertThat(bookings.get(actor, profile, event).booking().allowed()).isTrue();

		ReservationResponse own = bookings.book(actor, profile, event, pass).reservation();

		assertThat(entry(waiting.id())).containsEntry("status", "BOOKED").containsEntry("reservation_id", own.id());
		GroupClassResponse detail = bookings.get(actor, profile, event);
		assertThat(detail.waitlist().ownEntry()).isNull();
		assertThat(detail.waitlist().waitingCount()).isZero();
		assertThat(jdbc.sql("SELECT source FROM reservation WHERE id = :id").param("id", own.id())
			.query(String.class).single()).isEqualTo("SELF");
	}

	@Test
	void aGuardianJoinsForAChildAndTheChildGetsThePlaceWithTheirOwnPass() {
		UUID child = customer(null, "CHILD");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", profile).param("child", child).update();
		UUID childPass = pass(child);
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse waiting = waitlists.join(actor, child, event, childPass).entry();
		assertError(() -> waitlists.get(otherActor, child, waiting.id()), ErrorCode.NOT_FOUND);
		assertError(() -> bookings.get(otherActor, child, event), ErrorCode.NOT_FOUND);

		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		assertThat(bookings.get(actor, child, event).ownReservation().customerPassId()).isEqualTo(childPass);
		assertThat(jdbc.sql("SELECT booked_by FROM reservation WHERE customer_id = :child AND status = 'ACTIVE'")
			.param("child", child).query(UUID.class).single()).isEqualTo(actor);
		assertThat(balance(childPass)).isEqualTo(7);
	}

	@Test
	void anEntryOfAnAccountThatNoLongerActsForTheParticipantIsSkipped() {
		UUID child = customer(null, "CHILD");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent, :child)")
			.param("parent", profile).param("child", child).update();
		UUID childPass = pass(child);
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse childEntry = waitlists.join(actor, child, event, childPass).entry();
		waitlists.join(thirdActor, thirdProfile, event, thirdPass);
		jdbc.sql("UPDATE guardianship SET ended_at = now() WHERE child_id = :child").param("child", child).update();

		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		assertThat(entry(childEntry.id())).containsEntry("status", "WAITING");
		assertThat(bookings.get(thirdActor, thirdProfile, event).ownReservation()).isNotNull();
		assertThat(balance(childPass)).isEqualTo(8);
	}

	@Test
	void httpJoinReturnsTheEntryWithItsLocationAndLeavingReturnsItCancelled() throws Exception {
		bookings.book(otherActor, otherProfile, event, otherPass);
		String joinPath = base(profile) + "/group-classes/" + event + "/waitlist-entries";
		var result = mvc.perform(post(joinPath).header("Authorization", "Bearer " + PARENT_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"customerPassId\":\"" + pass + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.eventId").value(event.toString()))
			.andExpect(jsonPath("$.customerId").value(profile.toString()))
			.andExpect(jsonPath("$.customerPassId").value(pass.toString()))
			.andExpect(jsonPath("$.status").value("WAITING"))
			.andExpect(jsonPath("$.position").value(1))
			.andExpect(jsonPath("$.joinedAt").isNotEmpty())
			.andExpect(jsonPath("$.resolvedAt").isEmpty())
			.andExpect(jsonPath("$.reservationId").isEmpty())
			.andExpect(jsonPath("$.promotionBlockedReason").isEmpty())
			.andReturn();
		String entryId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		String location = base(profile) + "/waitlist-entries/" + entryId;
		assertThat(result.getResponse().getHeader("Location")).isEqualTo(location);
		mvc.perform(post(joinPath).header("Authorization", "Bearer " + PARENT_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"customerPassId\":\"" + pass + "\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(entryId));
		mvc.perform(get(location).header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isOk()).andExpect(jsonPath("$.position").value(1));
		mvc.perform(get(base(profile) + "/group-classes/" + event).header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.booking.reason").value("CLASS_FULL"))
			.andExpect(jsonPath("$.waitlist.allowed").value(false))
			.andExpect(jsonPath("$.waitlist.reason").value("WAITLIST_ALREADY_JOINED"))
			.andExpect(jsonPath("$.waitlist.waitingCount").value(1))
			.andExpect(jsonPath("$.waitlist.ownEntry.id").value(entryId))
			.andExpect(jsonPath("$.waitlist.ownEntry.position").value(1));
		mvc.perform(post(location + "/cancel").header("Authorization", "Bearer " + PARENT_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.position").isEmpty());
		mvc.perform(post(joinPath)).andExpect(status().isUnauthorized());
		mvc.perform(get(location).header("Authorization", "Bearer " + OTHER_TOKEN))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(post(joinPath).header("Authorization", "Bearer " + PARENT_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post(base(otherProfile) + "/group-classes/" + event + "/waitlist-entries")
			.header("Authorization", "Bearer " + OTHER_TOKEN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"customerPassId\":\"" + otherPass + "\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RESERVED"));
	}

	@Test
	void crossingPromotionsLockTheirPassesInOneOrderAndDoNotDeadlock() throws Exception {
		UUID secondEvent = event(1);
		ReservationResponse ownBooking = bookings.book(actor, profile, event, pass).reservation();
		ReservationResponse otherBooking = bookings.book(otherActor, otherProfile, secondEvent, otherPass).reservation();
		// Each participant waits for the other one's class with the pass that funds their own booking.
		waitlists.join(otherActor, otherProfile, event, otherPass);
		waitlists.join(actor, profile, secondEvent, pass);
		CountDownLatch bothCancelled = new CountDownLatch(2);
		// Both transactions hold their occurrence and reservation locks, and no pass lock yet.
		doAnswer(invocation -> {
			Object cancelled = invocation.callRealMethod();
			bothCancelled.countDown();
			if (!bothCancelled.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("The crossing cancellation never reached its promotion.");
			}
			return cancelled;
		}).when(reservations).cancel(any(), any(), any(), anyBoolean(), any());
		try (var executor = Executors.newFixedThreadPool(2)) {
			var own = executor.submit(cancellation(actor, profile, ownBooking.id()));
			var other = executor.submit(cancellation(otherActor, otherProfile, otherBooking.id()));
			assertThat(List.of(own.get(30, TimeUnit.SECONDS), other.get(30, TimeUnit.SECONDS)))
				.containsExactly("CANCELLED", "CANCELLED");
		}
		assertThat(bookings.get(otherActor, otherProfile, event).ownReservation().customerPassId()).isEqualTo(otherPass);
		assertThat(bookings.get(actor, profile, secondEvent).ownReservation().customerPassId()).isEqualTo(pass);
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(balance(otherPass)).isEqualTo(7);
		assertThat(activeReservations(event)).isEqualTo(1);
		assertThat(activeReservations(secondEvent)).isEqualTo(1);
	}

	@Test
	void aPassChangedAfterThePromotionCheckRefusesTheCancellationWithNothingChanged() {
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse waiting = waitlists.join(actor, profile, event, pass).entry();
		LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
		BookingEntitlements target = AopTestUtils.getUltimateTargetObject(entitlements);
		// The check still sees an entry that a concurrent booking has just spent.
		doAnswer(invocation -> List.of(new BookingEntitlements.EligiblePass(pass, "waitlist-test-pass", (short) 1, today,
				today.plusDays(29)))).when(target).eligible(eq(profile), eq(service), any(LocalDate.class));
		jdbc.sql("UPDATE customer_pass SET entries_remaining = 0 WHERE id = :pass").param("pass", pass).update();

		assertError(() -> bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true)),
				ErrorCode.VERSION_CONFLICT);

		assertThat(bookings.getReservation(otherActor, otherProfile, booked.id()).status()).isEqualTo("ACTIVE");
		assertThat(balance(otherPass)).isEqualTo(7);
		assertThat(entry(waiting.id())).containsEntry("status", "WAITING");
		assertThat(activeReservations(event)).isEqualTo(1);
		reset(target);
		var retried = bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));
		assertThat(retried.status()).isEqualTo("CANCELLED");
		assertThat(entry(waiting.id())).containsEntry("status", "WAITING");
		assertThat(bookings.get(actor, profile, event).availablePlaces()).isEqualTo(1);
	}

	@Test
	void concurrentJoinsOfOneParticipantCreateOneEntry() throws Exception {
		bookings.book(otherActor, otherProfile, event, otherPass);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(competingJoin(ready, start));
			var second = executor.submit(competingJoin(ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("CREATED", "EXISTING");
		}
		assertThat(waitlistRows()).isEqualTo(1);
	}

	@Test
	void aJoinRacingTheLastCancellationNeverOverfillsTheClass() throws Exception {
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var join = executor.submit(() -> {
				ready.countDown();
				start.await(10, TimeUnit.SECONDS);
				try {
					return waitlists.join(actor, profile, event, pass).entry().status();
				}
				catch (ApiException ex) {
					return ex.getErrorCode().name();
				}
			});
			var cancel = executor.submit(cancellation(otherActor, otherProfile, booked.id(), ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(cancel.get(20, TimeUnit.SECONDS)).isEqualTo("CANCELLED");
			// Either the join came first and got the freed place, or it found the place free.
			assertThat(join.get(20, TimeUnit.SECONDS)).isIn("WAITING", "CLASS_NOT_FULL");
		}
		GroupClassResponse detail = bookings.get(actor, profile, event);
		assertThat(activeReservations(event)).isLessThanOrEqualTo(1);
		assertThat(detail.ownReservation() != null || detail.availablePlaces() == 1).isTrue();
		assertThat(detail.waitlist().ownEntry()).isNull();
	}

	@Test
	void withAConfirmationTimeTheFreedPlaceIsHeldAndBookedOnlyOnceConfirmed() {
		confirmationMinutes(30);
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse first = waitlists.join(actor, profile, event, pass).entry();
		waitlists.join(thirdActor, thirdProfile, event, thirdPass);
		Instant cancelledAt = Instant.now();

		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		GroupClassResponse offered = bookings.get(actor, profile, event);
		assertThat(offered.ownReservation()).isNull();
		assertThat(offered.availablePlaces()).isZero();
		assertThat(offered.waitlist().confirmationMinutes()).isEqualTo(30);
		WaitlistEntryResponse offer = offered.waitlist().ownEntry();
		assertThat(offer.status()).isEqualTo("OFFERED");
		assertThat(offer.position()).isNull();
		assertThat(offer.offerExpiresAt()).isBetween(cancelledAt.plus(Duration.ofMinutes(29)),
				cancelledAt.plus(Duration.ofMinutes(31)));
		assertThat(balance(pass)).isEqualTo(8);
		// The held place is not free for anybody else, so the canceller cannot take it back.
		assertError(() -> bookings.book(otherActor, otherProfile, event, otherPass), ErrorCode.CLASS_FULL);
		assertThat(bookings.get(otherActor, otherProfile, event).waitlist().allowed()).isTrue();
		assertError(() -> waitlists.confirm(thirdActor, thirdProfile, waitlistEntryOf(thirdProfile)),
				ErrorCode.WAITLIST_ENTRY_NOT_OFFERED);

		WaitlistEntryResponse confirmed = waitlists.confirm(actor, profile, first.id());
		WaitlistEntryResponse again = waitlists.confirm(actor, profile, first.id());

		assertThat(confirmed.status()).isEqualTo("PROMOTED");
		assertThat(confirmed.offerExpiresAt()).isNull();
		assertThat(again).isEqualTo(confirmed);
		GroupClassResponse promoted = bookings.get(actor, profile, event);
		assertThat(promoted.ownReservation().id()).isEqualTo(confirmed.reservationId());
		assertThat(promoted.ownReservation().customerPassId()).isEqualTo(pass);
		assertThat(jdbc.sql("SELECT source, booked_by FROM reservation WHERE id = :id").param("id", confirmed.reservationId())
			.query().singleRow()).containsEntry("source", "WAITLIST").containsEntry("booked_by", actor);
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(activeReservations(event)).isEqualTo(1);
	}

	@Test
	void aDeclinedOrExpiredOfferPassesThePlaceOnAndCannotBeConfirmedLater() {
		confirmationMinutes(30);
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse first = waitlists.join(actor, profile, event, pass).entry();
		WaitlistEntryResponse second = waitlists.join(thirdActor, thirdProfile, event, thirdPass).entry();
		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		WaitlistEntryResponse declined = waitlists.leave(actor, profile, first.id());

		assertThat(declined.status()).isEqualTo("CANCELLED");
		assertThat(entry(second.id())).containsEntry("status", "OFFERED");
		expireOffers();
		assertError(() -> waitlists.confirm(thirdActor, thirdProfile, second.id()), ErrorCode.WAITLIST_OFFER_EXPIRED);
		// The next change of the class closes the offer before counting free places.
		assertThat(bookings.book(otherActor, otherProfile, event, otherPass).created()).isTrue();
		assertThat(entry(second.id())).containsEntry("status", "EXPIRED");
		assertError(() -> waitlists.confirm(thirdActor, thirdProfile, second.id()), ErrorCode.WAITLIST_OFFER_EXPIRED);
		assertThat(balance(thirdPass)).isEqualTo(8);
	}

	@Test
	void thePeriodicCheckPassesOnOffersOfAQuietClass() {
		confirmationMinutes(30);
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		WaitlistEntryResponse first = waitlists.join(actor, profile, event, pass).entry();
		WaitlistEntryResponse second = waitlists.join(thirdActor, thirdProfile, event, thirdPass).entry();
		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));
		assertThat(offerExpiry.eventsToSettle()).doesNotContain(event);
		expireOffers();

		assertThat(offerExpiry.eventsToSettle()).contains(event);
		offerExpiry.settle(event);

		assertThat(entry(first.id())).containsEntry("status", "EXPIRED");
		assertThat(entry(second.id())).containsEntry("status", "OFFERED");
		assertThat(offerExpiry.eventsToSettle()).doesNotContain(event);
		assertThat(waitlists.confirm(thirdActor, thirdProfile, second.id()).status()).isEqualTo("PROMOTED");
		assertThat(balance(thirdPass)).isEqualTo(7);
	}

	@Test
	void anOfferEndsWhenBookingCloses() {
		confirmationMinutes(600);
		ReservationResponse booked = bookings.book(otherActor, otherProfile, event, otherPass).reservation();
		waitlists.join(actor, profile, event, pass);
		startsIn(event, Duration.ofHours(2));
		Instant startsAt = jdbc.sql("SELECT starts_at FROM schedule_event WHERE id = :id").param("id", event)
			.query(OffsetDateTime.class).single().toInstant();

		bookings.cancel(otherActor, otherProfile, booked.id(), new CancelRequest(0, true));

		assertThat(bookings.get(actor, profile, event).waitlist().ownEntry().offerExpiresAt())
			.isEqualTo(startsAt.minus(Duration.ofMinutes(30)));
	}

	private void confirmationMinutes(int minutes) {
		jdbc.sql("INSERT INTO booking_policy (scope, event_id, waitlist_confirm_minutes) VALUES ('EVENT', :event, :minutes)")
			.param("event", event).param("minutes", minutes).update();
	}

	/** As if the time to confirm the open offers of the class had run out. */
	private void expireOffers() {
		jdbc.sql("UPDATE waitlist_entry SET offer_expires_at = now() - interval '1 second' WHERE event_id = :event AND status = 'OFFERED'")
			.param("event", event).update();
	}

	private UUID waitlistEntryOf(UUID customer) {
		return jdbc.sql("SELECT id FROM waitlist_entry WHERE event_id = :event AND customer_id = :customer AND status IN ('WAITING', 'OFFERED')")
			.param("event", event).param("customer", customer).query(UUID.class).single();
	}

	private Callable<String> cancellation(UUID customerActor, UUID customerProfile, UUID reservationId) {
		return () -> {
			try {
				return bookings.cancel(customerActor, customerProfile, reservationId, new CancelRequest(0, true)).status();
			}
			catch (ApiException ex) {
				return ex.getErrorCode().name();
			}
		};
	}

	private Callable<String> cancellation(UUID customerActor, UUID customerProfile, UUID reservationId,
			CountDownLatch ready, CountDownLatch start) {
		Callable<String> cancel = cancellation(customerActor, customerProfile, reservationId);
		return () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Concurrent cancellation start timed out.");
			}
			return cancel.call();
		};
	}

	private Callable<String> competingJoin(CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Concurrent join start timed out.");
			}
			try {
				return waitlists.join(actor, profile, event, pass).created() ? "CREATED" : "EXISTING";
			}
			catch (ApiException ex) {
				return ex.getErrorCode().name();
			}
		};
	}

	private static void assertError(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}

	private UUID account(String name) {
		return jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@waitlist.test").query(UUID.class).single();
	}

	private void signIn(UUID accountId, String token) {
		String uid = "waitlist-" + accountId;
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
				""").param("kind", kind).param("firstName", account == null ? "waitlist-test-child" : "waitlist-test-adult")
			.param("birthDate", LocalDate.now().minusYears("CHILD".equals(kind) ? 10 : 30))
			.param("account", account, java.sql.Types.OTHER).query(UUID.class).single();
	}

	private UUID pass(UUID customer) {
		LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total,
				entries_remaining, price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'waitlist-test-pass', 8, 8, 120, 'ACTIVE', :from, :until) RETURNING id
				""").param("customer", customer).param("variant", variant).param("from", today)
			.param("until", today.plusDays(29)).query(UUID.class).single();
	}

	private UUID event(int capacity) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility)
				VALUES ('CLASS', :service, 'waitlist-test-class', :startsAt, :endsAt, :capacity, 'PUBLIC') RETURNING id
				""").param("service", service).param("startsAt", utc(startsAt))
			.param("endsAt", utc(startsAt.plusSeconds(3600))).param("capacity", capacity).query(UUID.class).single();
	}

	/** Moves the class closer, e.g. past the booking deadline, keeping its reservations' snapshots. */
	private void startsIn(UUID eventId, Duration lead) {
		Instant start = Instant.now().plus(lead);
		jdbc.sql("UPDATE schedule_event SET starts_at = :start, ends_at = :end WHERE id = :id")
			.param("start", utc(start)).param("end", utc(start.plusSeconds(3600))).param("id", eventId).update();
	}

	private Map<String, Object> entry(UUID entryId) {
		return jdbc.sql("SELECT status, reservation_id, resolved_at FROM waitlist_entry WHERE id = :id")
			.param("id", entryId).query().singleRow();
	}

	private int waitlistRows() {
		return jdbc.sql("SELECT count(*) FROM waitlist_entry WHERE event_id = :event").param("event", event)
			.query(Integer.class).single();
	}

	private int activeReservations(UUID eventId) {
		return jdbc.sql("SELECT count(*) FROM reservation WHERE event_id = :event AND status = 'ACTIVE'")
			.param("event", eventId).query(Integer.class).single();
	}

	private int balance(UUID passId) {
		return jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id").param("id", passId)
			.query(Integer.class).single();
	}

	private static OffsetDateTime utc(Instant time) {
		return time.atOffset(ZoneOffset.UTC);
	}

}
