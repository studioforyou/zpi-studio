package studioforyou.pl.backend.scheduling.internal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.http.MediaType;

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

	@Test
	void consultationReservesOnlyOwnTimeAndRoomWithoutCreatingCustomerOrPassEntries() throws Exception {
		int customers = count("customer_profile");
		int reservations = count("reservation");
		int ledger = count("pass_ledger");
		String request = consultation(GYM, "  Jan Gość  ");
		String eventId = id(consult(ANNA_TOKEN, "", request).andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("CONSULTATION"))
			.andExpect(jsonPath("$.visibility").value("PRIVATE"))
			.andExpect(jsonPath("$.guestName").value("Jan Gość"))
			.andExpect(jsonPath("$.serviceTypeId").isEmpty())
			.andExpect(jsonPath("$.capacity").isEmpty())
			.andExpect(jsonPath("$.staff[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(true)));
		list(ANNA_TOKEN, at(this.monday, "00:00"), at(this.monday.plusDays(1), "00:00"))
			.andExpect(jsonPath("$.items[*].id").value(contains(eventId)));
		this.mockMvc.perform(as(MAREK_TOKEN, MockMvcRequestBuilders.get(PATH + "/" + eventId)))
			.andExpect(status().isNotFound());
		assertThat(count("customer_profile")).isEqualTo(customers);
		assertThat(count("reservation")).isEqualTo(reservations);
		assertThat(count("pass_ledger")).isEqualTo(ledger);
	}

	@Test
	void consultationPreviewDoesNotSaveAndConflictsPreventBooking() throws Exception {
		String request = consultation(POLE_ROOM, "Jan");
		consult(ANNA_TOKEN, "/preview", request).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(0));
		assertThat(count("schedule_event")).isZero();
		createEvent(poleClass(this.monday, "18:00", "19:00"));
		consult(MAREK_TOKEN, "/preview", request).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1));
		consult(MAREK_TOKEN, "", request).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"));
		consult(ANNA_TOKEN, "", consultation(GYM, "" )).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"));
		assertThat(count("schedule_event")).isEqualTo(1);
	}

	@Test
	void consultationNeedsActiveRoomFutureValidTimesAndTrainerProfile() throws Exception {
		consult(ANNA_TOKEN, "", consultation(CLOSED_ROOM, "Jan")).andExpect(status().isUnprocessableEntity());
		consult(ANNA_TOKEN, "", consultation(UUID.randomUUID(), "Jan")).andExpect(status().isUnprocessableEntity());
		consult(ANNA_TOKEN, "", consultation(GYM, "a".repeat(121))).andExpect(status().isBadRequest());
		String request = consultation(GYM, "");
		consult(TRAINER_TOKEN, "", request).andExpect(status().isNotFound());
		consult(ADMIN_TOKEN, "", request).andExpect(status().isForbidden());
		this.anonymous.perform(MockMvcRequestBuilders.post(PATH + "/consultations")
			.contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isUnauthorized());
		consult(ANNA_TOKEN, "/preview", request.replace(at(this.monday, "19:00").toString(),
			at(this.monday, "17:00").toString())).andExpect(status().isBadRequest());
		consult(ANNA_TOKEN, "", request.replace(at(this.monday, "18:00").toString(),
			Instant.now().minusSeconds(3600).toString())).andExpect(status().isBadRequest());
	}

	@Test
	void consultationWithoutGuestIsAllowedButApprovedLeaveBlocksIt() throws Exception {
		consult(ANNA_TOKEN, "", consultation(GYM, "   ")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.guestName").isEmpty());
		insertTimeOff(MAREK, at(this.monday, "17:00"), at(this.monday, "20:00"), "APPROVED");
		consult(MAREK_TOKEN, "/preview", consultation(POLE_ROOM, "Jan")).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1));
		consult(MAREK_TOKEN, "", consultation(POLE_ROOM, "Jan")).andExpect(status().isConflict());
	}

	private int count(String table) {
		return this.jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private String consultation(UUID roomId, String guest) {
		return """
				{"resourceId":"%s","guestName":"%s","startsAt":"%s","endsAt":"%s"}
				""".formatted(roomId, guest, at(this.monday, "18:00"), at(this.monday, "19:00"));
	}

	private ResultActions consult(String token, String suffix, String json) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.post(PATH + "/consultations" + suffix)
			.contentType(MediaType.APPLICATION_JSON).content(json)));
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
