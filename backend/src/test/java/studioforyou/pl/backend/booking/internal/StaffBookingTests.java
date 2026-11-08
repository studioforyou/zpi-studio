package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancelRequest;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.GroupClassResponse;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Administrators book and cancel group-class places on behalf of participants (#110). Firebase ID
 * tokens are mocked at the {@link JwtDecoder}; requests run in their own transactions, as in
 * production, and the data is removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StaffBookingTests {

	private static final String ADMIN_TOKEN = "staff-booking-admin-token";

	private static final String CLIENT_TOKEN = "staff-booking-client-token";

	private static final String TRAINER_TOKEN = "staff-booking-trainer-token";

	private static final String EMAIL_DOMAIN = "@staff-booking.test";

	private static final String LAST_NAME = "staff-booking-";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private GroupClassBookingService bookings;

	@Autowired
	private GroupClassWaitlistService waitlists;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID admin;

	private UUID client;

	private UUID profile;

	private UUID otherClient;

	private UUID otherProfile;

	private UUID service;

	private UUID product;

	private UUID variant;

	private UUID pass;

	private UUID otherPass;

	private Instant startsAt;

	private UUID event;

	@BeforeEach
	void setUp() {
		admin = account("admin", "ADMIN", ADMIN_TOKEN);
		jdbc.sql("INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:account, 'Beata', 'Wrona')")
			.param("account", admin).update();
		account("trainer", "TRAINER", TRAINER_TOKEN);
		client = account("client", "CLIENT", CLIENT_TOKEN);
		otherClient = account("other", "CLIENT", null);
		profile = customer(client, "Ola", "Nowak", LocalDate.now().minusYears(30));
		otherProfile = customer(otherClient, "Jan", "Adamski", LocalDate.now().minusYears(30));
		service = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, max_participants)
				VALUES ('staff-booking-group', 'GROUP', 60, 12) RETURNING id
				""").query(UUID.class).single();
		product = jdbc.sql("""
				INSERT INTO pass_product (name, kind, audience) VALUES ('staff-booking-pass', 'ENTRY_PASS', 'ANY') RETURNING id
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
		event = event(2, "CLASS", "PUBLIC", startsAt);
	}

	@AfterEach
	void cleanUp() {
		String events = "SELECT id FROM schedule_event WHERE service_type_id = '" + service + "'";
		String reservations = "SELECT id FROM reservation WHERE event_id IN (" + events + ")";
		String accounts = "SELECT id FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'";
		jdbc.sql("DELETE FROM audit_log WHERE entity_id IN (" + reservations + ")").update();
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :variant)")
			.param("variant", variant).update();
		jdbc.sql("DELETE FROM waitlist_entry WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM booking_policy WHERE service_type_id = :service OR event_id IN (" + events + ")")
			.param("service", service).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM customer_pass WHERE variant_id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE id = :variant").param("variant", variant).update();
		jdbc.sql("DELETE FROM pass_product WHERE id = :product").param("product", product).update();
		jdbc.sql("DELETE FROM service_type WHERE id = :service").param("service", service).update();
		jdbc.sql("DELETE FROM customer_profile WHERE last_name LIKE '" + LAST_NAME + "%'").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'").update();
	}

	@Test
	void anAdministratorBooksAPlaceOnTheParticipantsPassAndTheParticipantManagesIt() throws Exception {
		perform(get(options(event, profile)), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.allowed").value(true))
			.andExpect(jsonPath("$.issues").isEmpty())
			.andExpect(jsonPath("$.clientBookingReason").value(nullValue()))
			.andExpect(jsonPath("$.eligiblePasses[0].id").value(pass.toString()));

		String body = book(event, profile, pass, "[]", "Ustalone telefonicznie").andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerId").value(profile.toString()))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.source").value("STAFF"))
			.andExpect(jsonPath("$.bookedBy").value("Beata Wrona"))
			.andExpect(jsonPath("$.bookingReason").value("Ustalone telefonicznie"))
			.andExpect(jsonPath("$.overriddenIssues").isEmpty())
			.andExpect(jsonPath("$.customerPassId").value(pass.toString()))
			.andExpect(jsonPath("$.passName").value("staff-booking-pass"))
			.andExpect(jsonPath("$.cancellation.allowed").value(true))
			.andExpect(jsonPath("$.cancellation.suggestedEntryReturned").value(true))
			.andExpect(jsonPath("$.cancellation.participant.allowed").value(true))
			.andReturn().getResponse().getContentAsString();
		UUID reservation = UUID.fromString(JsonPath.read(body, "$.reservationId"));
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(jdbc.sql("SELECT booked_by FROM reservation WHERE id = :id").param("id", reservation)
			.query(UUID.class).single()).isEqualTo(admin);
		assertThat(actions(reservation)).containsExactly(StaffBookingService.BOOKED);

		// A retry returns the place without charging another entry.
		book(event, profile, pass, "[]", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.reservationId").value(reservation.toString()));
		assertThat(balance(pass)).isEqualTo(7);
		perform(get(options(event, profile)), ADMIN_TOKEN)
			.andExpect(jsonPath("$.allowed").value(false))
			.andExpect(jsonPath("$.reason").value("ALREADY_RESERVED"))
			.andExpect(jsonPath("$.reservationId").value(reservation.toString()));
		perform(get(base(event) + "/reservations"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.bookable").value(true))
			.andExpect(jsonPath("$.activeReservations").value(1))
			.andExpect(jsonPath("$.availablePlaces").value(1))
			.andExpect(jsonPath("$.participants[0].displayName").value("Ola " + LAST_NAME + "Nowak"))
			.andExpect(jsonPath("$.participants[0].bookedBy").value("Beata Wrona"));

		// The participant sees the place and cancels it on the terms stored at booking.
		GroupClassResponse own = bookings.list(client, profile, startsAt.minusSeconds(1), startsAt.plusSeconds(60), true)
			.getFirst();
		assertThat(own.ownReservation().id()).isEqualTo(reservation);
		bookings.cancel(client, profile, reservation, new CancelRequest(0, true));
		assertThat(balance(pass)).isEqualTo(8);
	}

	@Test
	void theBookingWindowDoesNotBindAdministratorsButAFullClassHasToBeConfirmed() throws Exception {
		jdbc.sql("INSERT INTO booking_policy (scope, event_id, booking_closes_minutes_before) VALUES ('EVENT', :event, 10000)")
			.param("event", event).update();
		jdbc.sql("UPDATE schedule_event SET capacity = 1 WHERE id = :id").param("id", event).update();
		perform(get(options(event, otherProfile)), ADMIN_TOKEN)
			.andExpect(jsonPath("$.allowed").value(true))
			.andExpect(jsonPath("$.issues").isEmpty())
			.andExpect(jsonPath("$.clientBookingReason").value("BOOKING_CLOSED"));
		book(event, otherProfile, otherPass, "[]", null).andExpect(status().isCreated());

		perform(get(options(event, profile)), ADMIN_TOKEN)
			.andExpect(jsonPath("$.allowed").value(true))
			.andExpect(jsonPath("$.issues").value(contains("CLASS_FULL")));
		book(event, profile, pass, "[]", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING_OVERRIDE_REQUIRED"))
			.andExpect(jsonPath("$.issues").value(contains("CLASS_FULL")));
		assertThat(balance(pass)).isEqualTo(8);
		book(event, profile, pass, "[\"CLASS_FULL\"]", null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.overriddenIssues").value(contains("CLASS_FULL")));
		perform(get(base(event) + "/reservations"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.activeReservations").value(2))
			.andExpect(jsonPath("$.availablePlaces").value(0))
			.andExpect(jsonPath("$.clientBookingReason").value("BOOKING_CLOSED"));
	}

	@Test
	void unmetRequirementsAreOverriddenOnceConfirmedAndWithoutAPassNoEntryIsCharged() throws Exception {
		jdbc.sql("UPDATE service_type SET min_age = 40 WHERE id = :id").param("id", service).update();
		jdbc.sql("UPDATE customer_pass SET status = 'CANCELLED' WHERE id = :id").param("id", pass).update();
		perform(get(options(event, profile)), ADMIN_TOKEN)
			.andExpect(jsonPath("$.issues").value(contains("AGE_RESTRICTION", "NO_ELIGIBLE_PASS")))
			.andExpect(jsonPath("$.eligiblePasses").isEmpty());
		book(event, profile, null, "[\"AGE_RESTRICTION\"]", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.issues").value(contains("AGE_RESTRICTION", "NO_ELIGIBLE_PASS")));
		String body = book(event, profile, null, "[\"AGE_RESTRICTION\", \"NO_ELIGIBLE_PASS\"]", null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerPassId").value(nullValue()))
			.andExpect(jsonPath("$.passName").value(nullValue()))
			.andExpect(jsonPath("$.cancellation.suggestedEntryReturned").value(nullValue()))
			.andExpect(jsonPath("$.overriddenIssues").value(contains("AGE_RESTRICTION", "NO_ELIGIBLE_PASS")))
			.andReturn().getResponse().getContentAsString();
		UUID reservation = UUID.fromString(JsonPath.read(body, "$.reservationId"));
		assertThat(ledgerRows()).isZero();

		// Without a pass the participant cancels without any entry consequence to confirm.
		var own = bookings.get(client, profile, event).ownReservation();
		assertThat(own.cancellation().allowed()).isTrue();
		assertThat(own.cancellation().entryReturned()).isNull();
		assertThatThrownBy(() -> bookings.cancel(client, profile, reservation, new CancelRequest(0, true)))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CANCELLATION_CONSEQUENCE_CHANGED);
			});
		var cancelled = bookings.cancel(client, profile, reservation, new CancelRequest(0, null));
		assertThat(cancelled.status()).isEqualTo("CANCELLED");
		assertThat(cancelled.entryReturned()).isNull();
		assertThat(ledgerRows()).isZero();
	}

	@Test
	void aPassIsRequiredWhenTheParticipantHasOneAndMustFundTheClass() throws Exception {
		book(event, profile, null, "[\"NO_ELIGIBLE_PASS\"]", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		book(event, profile, otherPass, "[]", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_NOT_USABLE"));
		perform(post(base(event) + "/reservations").contentType(MediaType.APPLICATION_JSON).content("{}"), ADMIN_TOKEN)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(reservationRows()).isZero();
		assertThat(balance(pass)).isEqualTo(8);
		assertThat(balance(otherPass)).isEqualTo(8);
	}

	@Test
	void anAdministratorDecidesAboutTheEntryAlsoWhenTheParticipantCannotCancel() throws Exception {
		jdbc.sql("""
				INSERT INTO booking_policy (scope, event_id, free_cancel_hours, late_cancel_allowed, late_cancel_forfeits_entry)
				VALUES ('EVENT', :event, 1000, false, true)
				""").param("event", event).update();
		UUID reservation = id(book(event, profile, pass, "[]", null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.cancellation.allowed").value(true))
			.andExpect(jsonPath("$.cancellation.late").value(true))
			.andExpect(jsonPath("$.cancellation.suggestedEntryReturned").value(false))
			.andExpect(jsonPath("$.cancellation.participant.allowed").value(false))
			.andExpect(jsonPath("$.cancellation.participant.reason").value("FREE_CANCELLATION_EXPIRED")));
		String cancel = base(event) + "/reservations/" + reservation + "/cancel";

		perform(post(cancel).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"), ADMIN_TOKEN)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		perform(post(cancel).contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":1,\"returnEntry\":true}"), ADMIN_TOKEN)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		perform(post(cancel).contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"returnEntry\":true,\"reason\":\" Choroba \"}"), ADMIN_TOKEN)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.entryReturned").value(true))
			.andExpect(jsonPath("$.cancellation.allowed").value(false))
			.andExpect(jsonPath("$.cancellation.reason").value("RESERVATION_NOT_ACTIVE"));
		assertThat(balance(pass)).isEqualTo(8);
		assertThat(jdbc.sql("SELECT cancel_kind FROM reservation WHERE id = :id").param("id", reservation)
			.query(String.class).single()).isEqualTo("LATE");
		assertThat(actions(reservation)).containsExactly(StaffBookingService.BOOKED, StaffBookingService.CANCELLED);
		assertThat(jdbc.sql("SELECT reason FROM audit_log WHERE entity_id = :id AND action = :action")
			.param("id", reservation).param("action", StaffBookingService.CANCELLED).query(String.class).single())
			.isEqualTo("Choroba");

		// Repeating it changes nothing.
		perform(post(cancel).contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"returnEntry\":false}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.entryReturned").value(true));
		assertThat(balance(pass)).isEqualTo(8);
		perform(get(base(event) + "/reservations"), ADMIN_TOKEN).andExpect(jsonPath("$.participants").isEmpty());
	}

	@Test
	void aForfeitedEntryStaysUsed() throws Exception {
		UUID reservation = id(book(event, profile, pass, "[]", null).andExpect(status().isCreated()));
		perform(post(base(event) + "/reservations/" + reservation + "/cancel").contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"returnEntry\":false}"), ADMIN_TOKEN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.entryReturned").value(false));
		assertThat(balance(pass)).isEqualTo(7);
		assertThat(jdbc.sql("SELECT cancel_kind FROM reservation WHERE id = :id").param("id", reservation)
			.query(String.class).single()).isEqualTo("IN_TIME");
	}

	@Test
	void theParticipantSeesAndCancelsAPrivateClassAnAdministratorBookedThem() throws Exception {
		UUID privateClass = event(4, "CLASS", "PRIVATE", startsAt);
		Instant from = startsAt.minusSeconds(1);
		Instant to = startsAt.plusSeconds(60);
		assertThat(bookings.list(client, profile, from, to, false))
			.extracting(GroupClassResponse::id).containsExactly(event);
		UUID reservation = id(book(privateClass, profile, pass, "[]", null).andExpect(status().isCreated()));

		assertThat(bookings.list(client, profile, from, to, false))
			.extracting(GroupClassResponse::id).containsExactly(event);
		assertThat(bookings.list(client, profile, from, to, true))
			.extracting(GroupClassResponse::id).containsExactly(privateClass);
		assertThat(bookings.get(client, profile, privateClass).ownReservation().id()).isEqualTo(reservation);
		assertThat(bookings.getReservation(client, profile, reservation).status()).isEqualTo("ACTIVE");
		bookings.cancel(client, profile, reservation, new CancelRequest(0, true));
		assertThat(balance(pass)).isEqualTo(8);
		// Without a reservation the private class stays hidden.
		perform(get("/api/v1/me/profiles/" + profile + "/group-classes/" + privateClass), CLIENT_TOKEN)
			.andExpect(status().isNotFound());
	}

	@Test
	void startedCancelledAndOtherEventsCannotBeChanged() throws Exception {
		UUID reservation = id(book(event, profile, pass, "[]", null).andExpect(status().isCreated()));
		jdbc.sql("UPDATE schedule_event SET starts_at = :start, ends_at = :end WHERE id = :id")
			.param("start", utc(Instant.now().minusSeconds(60))).param("end", utc(Instant.now().plusSeconds(3600)))
			.param("id", event).update();
		book(event, otherProfile, otherPass, "[]", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_BOOKABLE"));
		perform(post(base(event) + "/reservations/" + reservation + "/cancel").contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"returnEntry\":true}"), ADMIN_TOKEN)
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVENT_ALREADY_STARTED"));
		perform(get(base(event) + "/reservations"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.bookable").value(false))
			.andExpect(jsonPath("$.bookableReason").value("EVENT_NOT_BOOKABLE"))
			.andExpect(jsonPath("$.participants[0].cancellation.reason").value("EVENT_ALREADY_STARTED"));

		UUID cancelledClass = event(4, "CLASS", "PUBLIC", startsAt);
		jdbc.sql("UPDATE schedule_event SET status = 'CANCELLED' WHERE id = :id").param("id", cancelledClass).update();
		book(cancelledClass, profile, pass, "[]", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_BOOKABLE"));
		UUID personal = event(1, "PERSONAL_TRAINING", "PRIVATE", startsAt);
		book(personal, profile, pass, "[]", null).andExpect(status().isNotFound());
		perform(get(base(personal) + "/reservations"), ADMIN_TOKEN).andExpect(status().isNotFound());
		jdbc.sql("UPDATE customer_profile SET status = 'ARCHIVED' WHERE id = :id").param("id", otherProfile).update();
		UUID future = event(4, "CLASS", "PUBLIC", startsAt);
		perform(get(options(future, otherProfile)), ADMIN_TOKEN)
			.andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.reason").value("CUSTOMER_NOT_ACTIVE"));
		book(future, otherProfile, otherPass, "[]", null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ACTIVE"));
		assertThat(balance(pass)).isEqualTo(7);
	}

	@Test
	void bookingAWaitingParticipantTakesThemOffTheListAndACancelledPlaceGoesToTheQueue() throws Exception {
		jdbc.sql("UPDATE schedule_event SET capacity = 1 WHERE id = :id").param("id", event).update();
		UUID third = customer(account("third", "CLIENT", null), "Ewa", "Lis", LocalDate.now().minusYears(30));
		UUID thirdPass = pass(third);
		UUID thirdAccount = jdbc.sql("SELECT account_id FROM customer_profile WHERE id = :id").param("id", third)
			.query(UUID.class).single();
		UUID placed = bookings.book(otherClient, otherProfile, event, otherPass).reservation().id();
		waitlists.join(client, profile, event, pass);
		waitlists.join(thirdAccount, third, event, thirdPass);
		perform(get(options(event, profile)), ADMIN_TOKEN)
			.andExpect(jsonPath("$.waitlistStatus").value("WAITING"))
			.andExpect(jsonPath("$.issues").value(contains("CLASS_FULL")));

		book(event, profile, pass, "[\"CLASS_FULL\"]", null).andExpect(status().isCreated());
		assertThat(waitlistStatus(profile)).isEqualTo("BOOKED");
		perform(get(base(event) + "/reservations"), ADMIN_TOKEN).andExpect(jsonPath("$.waitingCount").value(1));

		// Over capacity, the freed place keeps the class full, so nobody is promoted yet.
		cancel(event, placed, true);
		assertThat(waitlistStatus(third)).isEqualTo("WAITING");
		UUID booked = jdbc.sql("SELECT id FROM reservation WHERE event_id = :event AND customer_id = :customer AND status = 'ACTIVE'")
			.param("event", event).param("customer", profile).query(UUID.class).single();
		cancel(event, booked, true);
		assertThat(waitlistStatus(third)).isEqualTo("PROMOTED");
		assertThat(balance(thirdPass)).isEqualTo(7);
		assertThat(balance(otherPass)).isEqualTo(8);
	}

	@Test
	void onlyAdministratorsManageOtherParticipantsPlaces() throws Exception {
		for (String token : List.of(CLIENT_TOKEN, TRAINER_TOKEN)) {
			perform(get(base(event) + "/reservations"), token).andExpect(status().isForbidden());
			perform(get(options(event, profile)), token).andExpect(status().isForbidden());
			book(event, profile, pass, "[]", null, token).andExpect(status().isForbidden());
		}
		mvc.perform(get(base(event) + "/reservations")).andExpect(status().isUnauthorized());
		assertThat(reservationRows()).isZero();
	}

	private ResultActions book(UUID eventId, UUID customerId, UUID passId, String accepted, String reason)
			throws Exception {
		return book(eventId, customerId, passId, accepted, reason, ADMIN_TOKEN);
	}

	private ResultActions book(UUID eventId, UUID customerId, UUID passId, String accepted, String reason, String token)
			throws Exception {
		String body = "{\"customerId\":\"%s\",\"customerPassId\":%s,\"acceptedIssues\":%s,\"reason\":%s}".formatted(
				customerId, passId == null ? "null" : "\"" + passId + "\"", accepted,
				reason == null ? "null" : "\"" + reason + "\"");
		return perform(post(base(eventId) + "/reservations").contentType(MediaType.APPLICATION_JSON).content(body), token);
	}

	private void cancel(UUID eventId, UUID reservation, boolean returnEntry) throws Exception {
		int version = jdbc.sql("SELECT version FROM reservation WHERE id = :id").param("id", reservation)
			.query(Integer.class).single();
		perform(post(base(eventId) + "/reservations/" + reservation + "/cancel").contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":%d,\"returnEntry\":%s}".formatted(version, returnEntry)), ADMIN_TOKEN)
			.andExpect(status().isOk());
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String token) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + token));
	}

	private static UUID id(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.reservationId"));
	}

	private static String base(UUID eventId) {
		return "/api/v1/schedule-events/" + eventId;
	}

	private static String options(UUID eventId, UUID customerId) {
		return base(eventId) + "/booking-options?customerId=" + customerId;
	}

	private UUID account(String name, String role, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + EMAIL_DOMAIN).query(UUID.class).single();
		String uid = "staff-booking-" + id;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id).param("role", role).update();
		if (token != null) {
			given(jwtDecoder.decode(token))
				.willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		}
		return id;
	}

	private UUID customer(UUID account, String firstName, String lastName, LocalDate birthDate) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, birth_date, account_id)
				VALUES ('ADULT', :firstName, :lastName, :birthDate, :account) RETURNING id
				""").param("firstName", firstName).param("lastName", LAST_NAME + lastName).param("birthDate", birthDate)
			.param("account", account).query(UUID.class).single();
	}

	private UUID pass(UUID customer) {
		LocalDate today = LocalDate.now(ZoneId.of("Europe/Warsaw"));
		return jdbc.sql("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total,
				entries_remaining, price_paid, status, valid_from, valid_until)
				VALUES (:customer, :variant, 'staff-booking-pass', 8, 8, 120, 'ACTIVE', :from, :until) RETURNING id
				""").param("customer", customer).param("variant", variant).param("from", today)
			.param("until", today.plusDays(29)).query(UUID.class).single();
	}

	private UUID event(int capacity, String kind, String visibility, Instant start) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity, visibility)
				VALUES (:kind, :service, 'staff-booking-class', :startsAt, :endsAt, :capacity, :visibility) RETURNING id
				""").param("kind", kind).param("service", service).param("startsAt", utc(start))
			.param("endsAt", utc(start.plusSeconds(3600))).param("capacity", capacity)
			.param("visibility", visibility).query(UUID.class).single();
	}

	private int balance(UUID passId) {
		return jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id").param("id", passId)
			.query(Integer.class).single();
	}

	private int reservationRows() {
		return jdbc.sql("SELECT count(*) FROM reservation WHERE event_id IN (SELECT id FROM schedule_event WHERE service_type_id = :service)")
			.param("service", service).query(Integer.class).single();
	}

	private int ledgerRows() {
		return jdbc.sql("SELECT count(*) FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = :variant)")
			.param("variant", variant).query(Integer.class).single();
	}

	private String waitlistStatus(UUID customer) {
		return jdbc.sql("SELECT status FROM waitlist_entry WHERE event_id = :event AND customer_id = :customer")
			.param("event", event).param("customer", customer).query(String.class).single();
	}

	private List<String> actions(UUID reservation) {
		return jdbc.sql("SELECT action FROM audit_log WHERE entity_id = :id ORDER BY occurred_at, id")
			.param("id", reservation).query(String.class).list();
	}

	private static OffsetDateTime utc(Instant time) {
		return time.atOffset(ZoneOffset.UTC);
	}

}
