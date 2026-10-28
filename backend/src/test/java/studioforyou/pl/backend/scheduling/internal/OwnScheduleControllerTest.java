package studioforyou.pl.backend.scheduling.internal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The signed-in trainer's own schedule.
 */
class OwnScheduleControllerTest extends ScheduleTestSupport {

	private static final String PATH = "/api/v1/me/schedule-events";

	@Test
	void trainerSeesOnlyTheEventsTheyRunOrAssistWithoutBlocks() throws Exception {
		LocalDate day = this.monday;
		String pole = createEvent(poleClass(day, "18:00", "19:00"));
		String assisted = createEvent(eventJson("WORKSHOP", POLE, at(day, "12:00"), at(day, "13:00"),
				staff(MAREK, "LEAD") + ", " + staff(ANNA, "ASSISTANT"), room(GYM, true)));
		String cancelled = createEvent(poleClass(day, "10:00", "11:00"));
		setStatus(cancelled, "CANCELLED");
		String marekOnly = createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(day, "07:00"), at(day, "08:00"),
				staff(MAREK, "LEAD"), room(GYM, false)));
		insertBlock(ANNA, at(day, "08:00"), at(day, "09:00"));
		createEvent(poleClass(day.plusDays(1), "18:00", "19:00"));

		Instant from = at(day, "00:00");
		Instant to = at(day.plusDays(1), "00:00");
		list(ANNA_TOKEN, from, to).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(contains(cancelled, assisted, pole)))
			.andExpect(jsonPath("$.items[0].status").value("CANCELLED"))
			.andExpect(jsonPath("$.items[1].staff[*].staffId").value(contains(MAREK.toString(), ANNA.toString())))
			.andExpect(jsonPath("$.items[2].resources[0].name").value("Pole room"));
		list(MAREK_TOKEN, from, to).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(contains(marekOnly, assisted)));
	}

	@Test
	void ownScheduleNeedsAValidWindow() throws Exception {
		Instant from = at(this.monday, "00:00");
		list(ANNA_TOKEN, from, from).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("to"));
		list(ANNA_TOKEN, from, from.plus(ScheduleEventController.MAX_WINDOW).plusSeconds(1))
			.andExpect(status().isBadRequest());
		list(ANNA_TOKEN, from, from.plus(ScheduleEventController.MAX_WINDOW)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void ownScheduleIsForTrainersWithAStaffProfile() throws Exception {
		Instant from = at(this.monday, "00:00");
		Instant to = at(this.monday.plusDays(1), "00:00");
		String window = "?from=" + from + "&to=" + to;

		this.anonymous.perform(MockMvcRequestBuilders.get(PATH + window)).andExpect(status().isUnauthorized());
		this.mockMvc.perform(MockMvcRequestBuilders.get(PATH + window))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		list(TRAINER_TOKEN, from, to).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	private ResultActions list(String token, Instant from, Instant to) throws Exception {
		return this.mockMvc.perform(as(token,
				MockMvcRequestBuilders.get(PATH).param("from", from.toString()).param("to", to.toString())));
	}

	private void insertBlock(UUID staffId, Instant startsAt, Instant endsAt) {
		UUID eventId = this.jdbcTemplate.queryForObject("""
				INSERT INTO schedule_event (kind, title, starts_at, ends_at, visibility, block_reason)
				VALUES ('BLOCK', 'Block', ?, ?, 'PRIVATE', 'OTHER') RETURNING id
				""", UUID.class, java.sql.Timestamp.from(startsAt), java.sql.Timestamp.from(endsAt));
		this.jdbcTemplate.update("INSERT INTO event_staff (event_id, staff_id) VALUES (?, ?)", eventId, staffId);
	}

}
