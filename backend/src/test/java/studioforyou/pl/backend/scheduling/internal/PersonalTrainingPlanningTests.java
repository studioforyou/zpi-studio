package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Real transactions and PostgreSQL: no orphan session, double entry or duplicate cooperation. */
class PersonalTrainingPlanningTests extends ScheduleTestSupport {

	private static final String PATH = "/api/v1/me/personal-trainings";
	private UUID customer, product, variant, pass, actor, document;

	@BeforeEach
	void setUpParticipant() {
		jdbcTemplate.update("UPDATE service_type SET category = 'PERSONAL_TRAINING' WHERE id = ?", PERSONAL);
		jdbcTemplate.update("INSERT INTO service_type_resource (service_type_id, resource_id) VALUES (?, ?)", PERSONAL, POLE_ROOM);
		actor = jdbcTemplate.queryForObject("SELECT account_id FROM staff_profile WHERE id = ?", UUID.class, MAREK);
		customer = jdbcTemplate.queryForObject("""
				INSERT INTO customer_profile (kind, first_name, last_name, birth_date)
				VALUES ('ADULT', 'New', 'Trainee', '1990-01-01') RETURNING id
				""", UUID.class);
		product = jdbcTemplate.queryForObject("INSERT INTO pass_product (name, kind, audience) VALUES ('Planning PT', 'PT_PACKAGE', 'ANY') RETURNING id", UUID.class);
		variant = jdbcTemplate.queryForObject("INSERT INTO pass_product_variant (product_id, entries_count, validity_days, price) VALUES (?, 8, 90, 800) RETURNING id", UUID.class, product);
		jdbcTemplate.update("INSERT INTO pass_product_service (product_id, service_type_id) VALUES (?, ?)", product, PERSONAL);
		pass = jdbcTemplate.queryForObject("""
				INSERT INTO customer_pass (customer_id, variant_id, product_name, entries_total, entries_remaining,
				                          price_paid, status, valid_from, valid_until)
				VALUES (?, ?, 'Planning PT', 8, 8, 800, 'ACTIVE', CURRENT_DATE, CURRENT_DATE + 90) RETURNING id
				""", UUID.class, customer, variant);
	}

	@AfterEach
	void removeParticipantData() {
		jdbcTemplate.update("DELETE FROM audit_log WHERE entity_id IN (SELECT id FROM reservation WHERE customer_id = ?)", customer);
		jdbcTemplate.update("DELETE FROM pass_ledger WHERE pass_id IN (SELECT id FROM customer_pass WHERE variant_id = ?)", variant);
		jdbcTemplate.update("DELETE FROM reservation WHERE customer_id = ?", customer);
		jdbcTemplate.update("DELETE FROM staff_assignment WHERE customer_id = ?", customer);
		jdbcTemplate.update("DELETE FROM pass_suspension WHERE pass_id = ?", pass);
		jdbcTemplate.update("DELETE FROM customer_pass WHERE variant_id = ?", variant);
		jdbcTemplate.update("DELETE FROM customer_profile WHERE id = ?", customer);
		jdbcTemplate.update("DELETE FROM pass_product_service WHERE product_id = ?", product);
		jdbcTemplate.update("DELETE FROM pass_product_variant WHERE id = ?", variant);
		jdbcTemplate.update("DELETE FROM pass_product WHERE id = ?", product);
		if (document != null) {
			jdbcTemplate.update("DELETE FROM document_service_requirement WHERE document_id = ?", document);
			jdbcTemplate.update("DELETE FROM document_version WHERE document_id = ?", document);
			jdbcTemplate.update("DELETE FROM document WHERE id = ?", document);
		}
	}

	@Test
	void previewChecksFundingWithoutCreatingAnything() throws Exception {
		preview(plan(GYM, "18:00", "19:00")).andExpect(status().isOk())
			.andExpect(jsonPath("$.participantReason").isEmpty())
			.andExpect(jsonPath("$.startsCooperation").value(true))
			.andExpect(jsonPath("$.eligiblePasses[0].id").value(pass.toString()))
			.andExpect(jsonPath("$.eligiblePasses[0].entriesRemaining").value(8));
		assertThat(count("schedule_event")).isZero();
		assertThat(count("reservation")).isZero();
		assertThat(count("staff_assignment")).isZero();
		assertThat(balance()).isEqualTo(8);
	}

	@Test
	void firstSessionCreatesPrivateScheduleBookingAndCooperationAndAllocatesOneEntry() throws Exception {
		String result = create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		UUID event = UUID.fromString(JsonPath.read(result, "$.eventId"));
		get("/api/v1/schedule-events/" + event).andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("PERSONAL_TRAINING"))
			.andExpect(jsonPath("$.visibility").value("PRIVATE"))
			.andExpect(jsonPath("$.title").value("Personal training — New Trainee"))
			.andExpect(jsonPath("$.capacity").value(1))
			.andExpect(jsonPath("$.staff[0].staffId").value(MAREK.toString()));
		assertThat(balance()).isEqualTo(7);
		assertThat(count("reservation")).isEqualTo(1);
		assertThat(count("staff_assignment")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT booked_by FROM reservation WHERE event_id = ?", UUID.class, event)).isEqualTo(actor);
		assertThat(jdbcTemplate.queryForObject("SELECT source FROM reservation WHERE event_id = ?", String.class, event)).isEqualTo("STAFF");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pass_ledger WHERE pass_id = ? AND kind = 'ENTRY_RESERVED'", Integer.class, pass)).isEqualTo(1);
		create(MAREK_TOKEN, GYM, "19:00", "20:00", pass).andExpect(status().isCreated());
		assertThat(count("staff_assignment")).isEqualTo(1);
		assertThat(balance()).isEqualTo(6);
	}

	@Test
	void failedFundingLeavesNoSessionReservationOrCooperation() throws Exception {
		create(MAREK_TOKEN, GYM, "18:00", "19:00", UUID.randomUUID()).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PASS_NOT_USABLE"));
		assertThat(count("schedule_event")).isZero();
		assertThat(count("reservation")).isZero();
		assertThat(count("staff_assignment")).isZero();
		assertThat(balance()).isEqualTo(8);
	}

	@Test
	void exhaustedExpiredAndSuspendedPassesAreNotOffered() throws Exception {
		jdbcTemplate.update("UPDATE customer_pass SET entries_remaining = 0 WHERE id = ?", pass);
		noPass();
		jdbcTemplate.update("UPDATE customer_pass SET entries_remaining = 8, valid_until = CURRENT_DATE - 1 WHERE id = ?", pass);
		noPass();
		jdbcTemplate.update("UPDATE customer_pass SET valid_until = CURRENT_DATE + 90 WHERE id = ?", pass);
		jdbcTemplate.update("INSERT INTO pass_suspension (pass_id, starts_on, ends_on, created_by) VALUES (?, CURRENT_DATE, CURRENT_DATE + 50, ?)", pass, actor);
		noPass();
	}

	@Test
	void anUnlimitedPassAllocatesUseWithoutInventingABalance() throws Exception {
		jdbcTemplate.update("UPDATE customer_pass SET entries_total = NULL, entries_remaining = NULL WHERE id = ?", pass);
		preview(plan(GYM, "18:00", "19:00")).andExpect(status().isOk())
			.andExpect(jsonPath("$.eligiblePasses[0].entriesRemaining").isEmpty());
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isCreated());
		assertThat(jdbcTemplate.queryForObject("SELECT entries_remaining FROM customer_pass WHERE id = ?", Integer.class, pass)).isNull();
	}

	@Test
	void trainerRoomAndParticipantConflictsAreCheckedAgainAtSave() throws Exception {
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isCreated());
		preview(plan(GYM, "18:30", "19:30")).andExpect(status().isOk())
			.andExpect(jsonPath("$.issues[*].type", hasItem("STAFF_BUSY")))
			.andExpect(jsonPath("$.participantReason").value("CUSTOMER_SCHEDULE_CONFLICT"));
		create(MAREK_TOKEN, GYM, "18:30", "19:30", pass).andExpect(status().isConflict());
		create(ANNA_TOKEN, POLE_ROOM, "18:30", "19:30", pass).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CUSTOMER_SCHEDULE_CONFLICT"));
		assertThat(count("schedule_event")).isEqualTo(1);
		assertThat(balance()).isEqualTo(7);
	}

	@Test
	void trainerLeaveBlocksPlanning() throws Exception {
		insertTimeOff(MAREK, at(monday, "17:00"), at(monday, "20:00"), "APPROVED");
		preview(plan(GYM, "18:00", "19:00")).andExpect(status().isOk())
			.andExpect(jsonPath("$.issues[*].type", hasItem("STAFF_ON_LEAVE")));
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isConflict());
		assertThat(count("reservation")).isZero();
	}

	@Test
	void inactiveRoomsAndInactiveServicesCannotBeUsed() throws Exception {
		create(MAREK_TOKEN, CLOSED_ROOM, "18:00", "19:00", pass).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESOURCE_UNAVAILABLE"));
		jdbcTemplate.update("UPDATE service_type SET is_active = FALSE WHERE id = ?", PERSONAL);
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_TYPE_UNAVAILABLE"));
		assertThat(count("schedule_event")).isZero();
	}

	@Test
	void validatesAgeAndRequiredDocumentsWithoutStartingCooperation() throws Exception {
		jdbcTemplate.update("UPDATE service_type SET min_age = 80 WHERE id = ?", PERSONAL);
		preview(plan(GYM, "18:00", "19:00")).andExpect(status().isOk())
			.andExpect(jsonPath("$.participantReason").value("AGE_RESTRICTION"));
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isUnprocessableContent());
		jdbcTemplate.update("UPDATE service_type SET min_age = NULL WHERE id = ?", PERSONAL);
		document = jdbcTemplate.queryForObject("INSERT INTO document (code, title, kind, is_required, blocks_booking) VALUES ('pt-planning', 'Consent', 'PARTICIPATION_CONSENT', TRUE, TRUE) RETURNING id", UUID.class);
		jdbcTemplate.update("INSERT INTO document_version (document_id, version_no, content, published_at) VALUES (?, 1, 'Consent', now())", document);
		jdbcTemplate.update("INSERT INTO document_service_requirement (document_id, service_type_id) VALUES (?, ?)", document, PERSONAL);
		preview(plan(GYM, "18:00", "19:00")).andExpect(status().isOk())
			.andExpect(jsonPath("$.participantReason").value("REQUIRED_DOCUMENTS_MISSING"));
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isUnprocessableContent());
		assertThat(count("schedule_event")).isZero();
		assertThat(count("staff_assignment")).isZero();
	}

	@Test
	void aChildCanBeBookedButAnArchivedProfileCannot() throws Exception {
		jdbcTemplate.update("UPDATE customer_profile SET kind = 'CHILD', birth_date = '2015-01-01' WHERE id = ?", customer);
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isCreated());
		jdbcTemplate.update("UPDATE customer_profile SET status = 'ARCHIVED' WHERE id = ?", customer);
		create(MAREK_TOKEN, GYM, "19:00", "20:00", pass).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_ACTIVE"));
		assertThat(balance()).isEqualTo(7);
	}

	@Test
	void rejectsPastGroupServicesAndMissingStaffAndRequiresTrainerRole() throws Exception {
		String past = plan(GYM, "18:00", "19:00").replace(at(monday, "18:00").toString(), Instant.now().minusSeconds(60).toString());
		preview(past).andExpect(status().isBadRequest());
		preview(plan(GYM, "18:00", "19:00").replace(PERSONAL.toString(), POLE.toString()))
			.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SERVICE_TYPE_UNAVAILABLE"));
		create(TRAINER_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isNotFound());
		create(ADMIN_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isForbidden());
		anonymous.perform(MockMvcRequestBuilders.post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content(createJson(GYM, "18:00", "19:00", pass))).andExpect(status().isUnauthorized());
	}

	@Test
	void twoTrainersCannotBookTheSameParticipantAtTheSameTime() throws Exception {
		try (var pool = Executors.newFixedThreadPool(2)) {
			var go = new CountDownLatch(1);
			var first = pool.submit(() -> { go.await(); return create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andReturn().getResponse().getStatus(); });
			var second = pool.submit(() -> { go.await(); return create(ANNA_TOKEN, POLE_ROOM, "18:00", "19:00", pass).andReturn().getResponse().getStatus(); });
			go.countDown();
			assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(201, 409);
		}
		assertThat(count("schedule_event")).isEqualTo(1);
		assertThat(count("reservation")).isEqualTo(1);
		assertThat(balance()).isEqualTo(7);
	}

	private void noPass() throws Exception {
		preview(plan(GYM, "18:00", "19:00")).andExpect(status().isOk())
			.andExpect(jsonPath("$.eligiblePasses").isEmpty()).andExpect(jsonPath("$.participantReason").value("NO_ELIGIBLE_PASS"));
		create(MAREK_TOKEN, GYM, "18:00", "19:00", pass).andExpect(status().isUnprocessableContent());
		assertThat(count("schedule_event")).isZero();
	}

	private int balance() { return jdbcTemplate.queryForObject("SELECT entries_remaining FROM customer_pass WHERE id = ?", Integer.class, pass); }
	private int count(String table) { return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
	private String plan(UUID room, String start, String end) {
		return """
				{"customerId":"%s","serviceTypeId":"%s","resourceId":"%s","startsAt":"%s","endsAt":"%s"}
				""".formatted(customer, PERSONAL, room, at(monday, start), at(monday, end));
	}
	private String createJson(UUID room, String start, String end, UUID passId) {
		return "{\"plan\":" + plan(room, start, end) + ",\"customerPassId\":\"" + passId + "\"}";
	}
	private ResultActions preview(String plan) throws Exception {
		return anonymous.perform(as(MAREK_TOKEN, MockMvcRequestBuilders.post(PATH + "/preview")).contentType(MediaType.APPLICATION_JSON).content(plan));
	}
	private ResultActions create(String token, UUID room, String start, String end, UUID passId) throws Exception {
		return anonymous.perform(as(token, MockMvcRequestBuilders.post(PATH)).contentType(MediaType.APPLICATION_JSON)
			.content(createJson(room, start, end, passId)));
	}
}
