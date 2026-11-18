package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class OwnSubstitutionControllerTest extends ScheduleTestSupport {

	private static final String REQUESTS = "/api/v1/me/substitution-requests";

	@Test
	void requestAndWithdrawalLeaveTheEventBookingsAndStaffUnchanged() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		int reservations = count("reservation");
		int ledger = count("pass_ledger");
		state(ANNA_TOKEN, event).andExpect(jsonPath("$.canRequest").value(true))
			.andExpect(jsonPath("$.request").isEmpty());
		String request = id(create(ANNA_TOKEN, event, "{\"reason\":\"  Wyjazd  \"}")
			.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.reason").value("Wyjazd"))
			.andExpect(jsonPath("$.canCancel").value(true)));
		state(ANNA_TOKEN, event).andExpect(jsonPath("$.canRequest").value(false))
			.andExpect(jsonPath("$.request.id").value(request));
		list(ANNA_TOKEN).andExpect(jsonPath("$.items[0].id").value(request));
		list(MAREK_TOKEN).andExpect(jsonPath("$.items").isEmpty());
		cancel(ANNA_TOKEN, request).andExpect(status().isNoContent());
		state(ANNA_TOKEN, event).andExpect(jsonPath("$.canRequest").value(true));
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM substitution_request WHERE id=?",
				String.class, UUID.fromString(request))).isEqualTo("CANCELLED");
		assertThat(this.jdbcTemplate.queryForObject("SELECT version FROM schedule_event WHERE id=?",
				Integer.class, UUID.fromString(event))).isZero();
		assertThat(count("event_staff")).isEqualTo(1);
		assertThat(count("reservation")).isEqualTo(reservations);
		assertThat(count("pass_ledger")).isEqualTo(ledger);
		create(ANNA_TOKEN, event, "{}").andExpect(status().isCreated()).andExpect(jsonPath("$.reason").isEmpty());
	}

	@Test
	void requestNeedsCurrentLeadAndDoesNotExposeTheReasonToAnAssistant() throws Exception {
		String event = createEvent(eventJson("CLASS", POLE, at(this.monday, "18:00"), at(this.monday, "19:00"),
				staff(ANNA, "LEAD") + "," + staff(MAREK, "ASSISTANT"), room(POLE_ROOM, true)));
		String request = id(create(ANNA_TOKEN, event, "{\"reason\":\"Wizyta lekarska\"}")
			.andExpect(status().isCreated()));
		state(MAREK_TOKEN, event).andExpect(status().isOk())
			.andExpect(jsonPath("$.canRequest").value(false))
			.andExpect(jsonPath("$.request.reason").isEmpty())
			.andExpect(jsonPath("$.request.canCancel").value(false));
		create(MAREK_TOKEN, event, "{}").andExpect(status().isForbidden());
		cancel(MAREK_TOKEN, request).andExpect(status().isNotFound());
	}

	@Test
	void trainerCannotReadOrModifyAnotherTrainersEvent() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		state(MAREK_TOKEN, event).andExpect(status().isNotFound());
		create(MAREK_TOKEN, event, "{}").andExpect(status().isNotFound());
		create(ADMIN_TOKEN, event, "{}").andExpect(status().isForbidden());
		create(TRAINER_TOKEN, event, "{}").andExpect(status().isNotFound());
		this.anonymous.perform(MockMvcRequestBuilders.post(path(event)).contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isUnauthorized());
	}

	@Test
	void duplicatesAndWithdrawalAfterClaimingAreRejected() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String request = id(create(ANNA_TOKEN, event, "{}").andExpect(status().isCreated()));
		create(ANNA_TOKEN, event, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SUBSTITUTION_ALREADY_REQUESTED"));
		this.jdbcTemplate.update("UPDATE substitution_request SET status='CLAIMED', substitute_staff_id=? WHERE id=?",
				MAREK, UUID.fromString(request));
		state(ANNA_TOKEN, event).andExpect(jsonPath("$.request.status").value("CLAIMED"))
			.andExpect(jsonPath("$.request.canCancel").value(false));
		cancel(ANNA_TOKEN, request).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SUBSTITUTION_NOT_OPEN"));
	}

	@Test
	void pastCancelledAndInactiveTrainerEventsCannotBeRequested() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		setStatus(event, "CANCELLED");
		state(ANNA_TOKEN, event).andExpect(jsonPath("$.canRequest").value(false));
		create(ANNA_TOKEN, event, "{}").andExpect(status().isUnprocessableEntity());
		setStatus(event, "SCHEDULED");
		this.jdbcTemplate.update("UPDATE schedule_event SET starts_at=?, ends_at=? WHERE id=?",
				Timestamp.from(Instant.now().minusSeconds(3600)), Timestamp.from(Instant.now().minusSeconds(60)),
				UUID.fromString(event));
		create(ANNA_TOKEN, event, "{}").andExpect(status().isUnprocessableEntity());
		String upcoming = createEvent(poleClass(this.monday, "12:00", "13:00"));
		this.jdbcTemplate.update("UPDATE staff_profile SET status='INACTIVE' WHERE id=?", ANNA);
		create(ANNA_TOKEN, upcoming, "{}").andExpect(status().isUnprocessableEntity());
	}

	@Test
	void removedLeadDoesNotBlockTheNewLeadFromRequestingASubstitute() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String old = id(create(ANNA_TOKEN, event, "{}").andExpect(status().isCreated()));
		this.jdbcTemplate.update("UPDATE event_staff SET staff_id=? WHERE event_id=?", MAREK, UUID.fromString(event));
		state(MAREK_TOKEN, event).andExpect(jsonPath("$.canRequest").value(true));
		create(MAREK_TOKEN, event, "{}").andExpect(status().isCreated());
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM substitution_request WHERE id=?",
				String.class, UUID.fromString(old))).isEqualTo("EXPIRED");
		list(ANNA_TOKEN).andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void validatesReasonAndScheduleWindow() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		create(ANNA_TOKEN, event, "{\"reason\":\"" + "a".repeat(1001) + "\"}")
			.andExpect(status().isBadRequest());
		this.mockMvc.perform(as(ANNA_TOKEN, MockMvcRequestBuilders.get(REQUESTS)
			.param("from", at(this.monday, "00:00").toString()).param("to", at(this.monday, "00:00").toString())))
			.andExpect(status().isBadRequest());
	}

	@Test
	void simultaneousRequestsCreateOnlyOneRow() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		CyclicBarrier start = new CyclicBarrier(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { start.await(10, TimeUnit.SECONDS);
				return create(ANNA_TOKEN, event, "{}").andReturn().getResponse().getStatus(); });
			var second = executor.submit(() -> { start.await(10, TimeUnit.SECONDS);
				return create(ANNA_TOKEN, event, "{}").andReturn().getResponse().getStatus(); });
			assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(201, 409);
		}
		assertThat(count("substitution_request")).isEqualTo(1);
	}

	private ResultActions state(String token, String event) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.get(path(event))));
	}

	private ResultActions create(String token, String event, String json) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.post(path(event))
			.contentType(MediaType.APPLICATION_JSON).content(json)));
	}

	private ResultActions cancel(String token, String request) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.post(REQUESTS + "/" + request + "/cancel")));
	}

	private ResultActions list(String token) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.get(REQUESTS)
			.param("from", at(this.monday, "00:00").toString())
			.param("to", at(this.monday.plusDays(1), "00:00").toString())));
	}

	private static String path(String event) {
		return "/api/v1/me/schedule-events/" + event + "/substitution-request";
	}

	private int count(String table) {
		return this.jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

}
