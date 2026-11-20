package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A trainer moving one occurrence they lead to another time or room: only their own events, only
 * this occurrence, the same conflict checks as for an administrator, and change notices for
 * everyone else concerned.
 */
class OwnRescheduleTest extends ScheduleTestSupport {

	private static final String PATH = "/api/v1/me/schedule-events/";

	private static final String CUSTOMER_PREFIX = "Own-reschedule";

	@AfterEach
	void removeReservations() {
		this.jdbcTemplate.update("DELETE FROM reservation WHERE customer_id IN "
				+ "(SELECT id FROM customer_profile WHERE first_name = ?)", CUSTOMER_PREFIX);
		this.jdbcTemplate.update("DELETE FROM customer_profile WHERE first_name = ?", CUSTOMER_PREFIX);
	}

	@Test
	void trainerMovesOneOccurrenceOfTheirSeriesAndKeepsItsDetails() throws Exception {
		String seriesId = createTuesdaySeries(2);
		List<String> ids = occurrenceIds();
		LocalDate tuesday = this.monday.plusDays(1);
		LocalDate wednesday = tuesday.plusDays(1);

		reschedule(ANNA_TOKEN, ids.get(0), move(0, at(wednesday, "17:00"), at(wednesday, "18:00"), null))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(at(wednesday, "17:00").toString()))
			.andExpect(jsonPath("$.endsAt").value(at(wednesday, "18:00").toString()))
			.andExpect(jsonPath("$.seriesId").value(seriesId))
			.andExpect(jsonPath("$.isSeriesException").value(true))
			.andExpect(jsonPath("$.seriesChange.usualStartsAt").value(at(tuesday, "18:00").toString()))
			.andExpect(jsonPath("$.title").value("Pole dance"))
			.andExpect(jsonPath("$.capacity").value(10))
			.andExpect(jsonPath("$.staff.length()").value(1))
			.andExpect(jsonPath("$.staff[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.version").value(1));

		get("/api/v1/schedule-events/" + ids.get(1)).andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(at(tuesday.plusWeeks(1), "18:00").toString()))
			.andExpect(jsonPath("$.isSeriesException").value(false));
		get("/api/v1/recurrence-series/" + seriesId).andExpect(status().isOk())
			.andExpect(jsonPath("$.localStartTime").value("18:00"));
	}

	@Test
	void trainerMovesTheirEventToAnotherRoom() throws Exception {
		String id = createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday, "10:00"),
				at(this.monday, "11:00"), staff(MAREK, "LEAD"), room(GYM, false)));
		createEvent(eventJson("CLASS", POLE, at(this.monday, "12:00"), at(this.monday, "13:00"),
				staff(ANNA, "LEAD"), room(POLE_ROOM, true)));
		this.jdbcTemplate.update("INSERT INTO service_type_resource (service_type_id, resource_id) VALUES (?, ?)",
				PERSONAL, POLE_ROOM);

		reschedule(MAREK_TOKEN, id, move(0, at(this.monday, "12:00"), at(this.monday, "13:00"), POLE_ROOM))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"));

		reschedule(MAREK_TOKEN, id, move(0, at(this.monday, "14:00"), at(this.monday, "15:00"), POLE_ROOM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("PERSONAL_TRAINING"))
			.andExpect(jsonPath("$.resources.length()").value(1))
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(false));
	}

	@Test
	void aClosedRoomRejectsTheMove() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));

		reschedule(ANNA_TOKEN, id, move(0, at(this.monday, "18:00"), at(this.monday, "19:00"), CLOSED_ROOM))
			.andExpect(status().isUnprocessableEntity());
		get("/api/v1/schedule-events/" + id)
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void onlyTheLeadTrainerCanMoveTheEvent() throws Exception {
		String assisted = createEvent(eventJson("WORKSHOP", POLE, at(this.monday, "12:00"), at(this.monday, "13:00"),
				staff(MAREK, "LEAD") + ", " + staff(ANNA, "ASSISTANT"), room(GYM, true)));
		String block = createEvent("""
				{"kind": "BLOCK", "title": "Maintenance", "startsAt": "%s", "endsAt": "%s",
				 "staff": [], "resources": [%s]}
				""".formatted(at(this.monday, "08:00"), at(this.monday, "09:00"), room(GYM, true)));
		String body = move(0, at(this.monday, "15:00"), at(this.monday, "16:00"), null);

		reschedule(ANNA_TOKEN, assisted, body).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		reschedule(ANNA_TOKEN, block, body).andExpect(status().isNotFound());
		reschedule(ANNA_TOKEN, UUID.randomUUID().toString(), body).andExpect(status().isNotFound());
		reschedule(TRAINER_TOKEN, assisted, body).andExpect(status().isNotFound());
		preview(ANNA_TOKEN, assisted, body).andExpect(status().isNotFound());
		this.mockMvc
			.perform(MockMvcRequestBuilders.put(PATH + assisted + "/reschedule")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isForbidden());
		this.anonymous
			.perform(MockMvcRequestBuilders.put(PATH + assisted + "/reschedule")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnauthorized());

		get("/api/v1/schedule-events/" + assisted)
			.andExpect(jsonPath("$.startsAt").value(at(this.monday, "12:00").toString()));
	}

	@Test
	void theTrainersOtherEventsAndApprovedLeaveBlockTheNewTime() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		createEvent(eventJson("CLASS", POLE, at(this.monday, "20:00"), at(this.monday, "21:00"),
				staff(ANNA, "LEAD"), room(GYM, true)));
		LocalDate tuesday = this.monday.plusDays(1);
		insertTimeOff(ANNA, at(tuesday, "00:00"), at(tuesday.plusDays(1), "00:00"), "APPROVED");

		reschedule(ANNA_TOKEN, id, move(0, at(this.monday, "20:30"), at(this.monday, "21:30"), null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("STAFF_BUSY"));
		reschedule(ANNA_TOKEN, id, move(0, at(tuesday, "18:00"), at(tuesday, "19:00"), null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("STAFF_ON_LEAVE"));

		// Overlapping its own previous time is fine.
		reschedule(ANNA_TOKEN, id, move(0, at(this.monday, "18:30"), at(this.monday, "19:30"), null))
			.andExpect(status().isOk());
	}

	@Test
	void previewListsProblemsWithoutSaving() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		createEvent(eventJson("CLASS", POLE, at(this.monday, "20:00"), at(this.monday, "21:00"),
				staff(MAREK, "LEAD"), room(GYM, true)));
		insertTimeOff(ANNA, at(this.monday, "20:00"), at(this.monday, "22:00"), "REQUESTED");

		preview(ANNA_TOKEN, id, move(0, at(this.monday, "20:00"), at(this.monday, "21:00"), GYM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.occurrences.length()").value(1))
			.andExpect(jsonPath("$.occurrences[0].startsAt").value(at(this.monday, "20:00").toString()))
			.andExpect(jsonPath("$.occurrences[0].issues[?(@.type == 'RESOURCE_BUSY')].blocking").value(true))
			.andExpect(jsonPath("$.occurrences[0].issues[?(@.type == 'STAFF_LEAVE_REQUESTED')].blocking")
				.value(false))
			.andExpect(jsonPath("$.conflictCount").value(1));

		preview(ANNA_TOKEN, id, move(0, at(this.monday, "16:00"), at(this.monday, "17:00"), null))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(0));

		get("/api/v1/schedule-events/" + id)
			.andExpect(jsonPath("$.startsAt").value(at(this.monday, "18:00").toString()))
			.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void onlyAFutureScheduledEventCanBeMovedToAFutureTime() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		Instant past = Instant.now().minusSeconds(3600);

		reschedule(ANNA_TOKEN, id, move(0, past, past.plusSeconds(3600), null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startsAt"));
		reschedule(ANNA_TOKEN, id, move(0, at(this.monday, "19:00"), at(this.monday, "18:00"), null))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endsAt"));
		reschedule(ANNA_TOKEN, id, move(7, at(this.monday, "17:00"), at(this.monday, "18:00"), null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

		setStatus(id, "CANCELLED");
		reschedule(ANNA_TOKEN, id, move(0, at(this.monday, "17:00"), at(this.monday, "18:00"), null))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

		String started = createEvent(poleClass(this.monday.plusDays(2), "18:00", "19:00"));
		this.jdbcTemplate.update("UPDATE schedule_event SET starts_at = ?, ends_at = ? WHERE id = ?",
				Timestamp.from(past), Timestamp.from(past.plusSeconds(7200)), UUID.fromString(started));
		reschedule(ANNA_TOKEN, started, move(0, at(this.monday, "17:00"), at(this.monday, "18:00"), null))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
	}

	@Test
	void participantsAndOtherStaffAreToldButNotTheTrainerWhoMovedIt() throws Exception {
		String id = createEvent(eventJson("CLASS", POLE, at(this.monday, "18:00"), at(this.monday, "19:00"),
				staff(ANNA, "LEAD") + ", " + staff(MAREK, "ASSISTANT"), room(POLE_ROOM, true)));
		UUID customer = book(id);

		reschedule(ANNA_TOKEN, id, move(0, at(this.monday, "19:00"), at(this.monday, "20:00"), GYM))
			.andExpect(status().isOk());

		assertThat(this.jdbcTemplate.queryForList(
				"SELECT kind FROM schedule_change_notice WHERE customer_id = ?", String.class, customer))
			.containsExactly("CHANGED");
		staffChanges(MAREK_TOKEN).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("CHANGED"))
			.andExpect(jsonPath("$.items[0].previousStartsAt").value(at(this.monday, "18:00").toString()))
			.andExpect(jsonPath("$.items[0].startsAt").value(at(this.monday, "19:00").toString()))
			.andExpect(jsonPath("$.items[0].rooms[0]").value("Gym"));
		staffChanges(ANNA_TOKEN).andExpect(jsonPath("$.items.length()").value(0));
	}

	private ResultActions reschedule(String token, String eventId, String json) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.put(PATH + eventId + "/reschedule"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions preview(String token, String eventId, String json) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.post(PATH + eventId + "/reschedule-preview"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions staffChanges(String token) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.get("/api/v1/me/schedule-changes")))
			.andExpect(status().isOk());
	}

	private static String move(int version, Instant startsAt, Instant endsAt, UUID resourceId) {
		return """
				{"version": %d, "startsAt": "%s", "endsAt": "%s", "resourceId": %s}
				""".formatted(version, startsAt, endsAt, (resourceId != null) ? "\"" + resourceId + "\"" : "null");
	}

	private String createTuesdaySeries(int weeks) throws Exception {
		ResultActions created = post("/api/v1/recurrence-series", """
				{"kind": "CLASS", "serviceTypeId": "%s", "weekdays": [2], "localStartTime": "18:00",
				 "durationMinutes": 60, "startsOn": "%s", "endsOn": "%s",
				 "staff": [%s], "resources": [%s]}
				""".formatted(POLE, this.monday, this.monday.plusWeeks(weeks).minusDays(1), staff(ANNA, "LEAD"),
				room(POLE_ROOM, true)))
			.andExpect(status().isCreated());
		return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.items[0].id");
	}

	private List<String> occurrenceIds() throws Exception {
		return JsonPath.read(get("/api/v1/schedule-events", "from", at(this.monday, "00:00").toString(), "to",
				at(this.monday.plusWeeks(5), "00:00").toString(), "kind", "CLASS")
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.items[*].id");
	}

	/** An active reservation of a new customer; returns the customer. */
	private UUID book(String eventId) {
		UUID customer = this.jdbcTemplate.queryForObject(
				"INSERT INTO customer_profile (first_name, last_name) VALUES (?, ?) RETURNING id", UUID.class,
				CUSTOMER_PREFIX, UUID.randomUUID().toString().substring(0, 8));
		UUID admin = this.jdbcTemplate.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class,
				"admin" + TEST_EMAIL_DOMAIN);
		this.jdbcTemplate.update("""
				INSERT INTO reservation (event_id, customer_id, booked_by, free_cancel_until)
				VALUES (?, ?, ?, ?)
				""", UUID.fromString(eventId), customer, admin, Timestamp.from(at(this.monday, "06:00")));
		return customer;
	}

}
