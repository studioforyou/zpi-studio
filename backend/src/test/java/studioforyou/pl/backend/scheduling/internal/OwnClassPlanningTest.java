package studioforyou.pl.backend.scheduling.internal;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Group classes a trainer adds to their own schedule: one-off classes, recurring series, the
 * conflicts shown before saving and the rooms a trainer can pick from.
 */
class OwnClassPlanningTest extends ScheduleTestSupport {

	private static final String EVENTS = "/api/v1/me/schedule-events";

	private static final String SERIES = "/api/v1/me/recurrence-series";

	@Test
	void trainerAddsAPublicClassRunByThemselves() throws Exception {
		// Fields a trainer cannot choose are ignored: the class is always a public one run by them.
		String request = """
				{"kind": "PERSONAL_TRAINING", "visibility": "PRIVATE", "staff": [%s],
				 "serviceTypeId": "%s", "title": "Pole dance open", "capacity": 6,
				 "startsAt": "%s", "endsAt": "%s", "resources": [%s]}
				""".formatted(staff(MAREK, "LEAD"), POLE, at(this.monday, "18:00"), at(this.monday, "19:00"),
				room(POLE_ROOM, true));

		String created = id(postAs(ANNA_TOKEN, EVENTS, request).andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("CLASS"))
			.andExpect(jsonPath("$.visibility").value("PUBLIC"))
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.title").value("Pole dance open"))
			.andExpect(jsonPath("$.capacity").value(6))
			.andExpect(jsonPath("$.staff.length()").value(1))
			.andExpect(jsonPath("$.staff[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.staff[0].role").value("LEAD"))
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString())));

		listOwn(ANNA_TOKEN).andExpect(jsonPath("$.items[*].id").value(contains(created)));
		listOwn(MAREK_TOKEN).andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void previewShowsConflictsWithoutSaving() throws Exception {
		String annasClass = createEvent(poleClass(this.monday, "18:00", "19:00"));

		postAs(MAREK_TOKEN, EVENTS + "/preview", oneOff(POLE_ROOM, "18:30", "19:30")).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences[0].issues[0].type").value("RESOURCE_BUSY"))
			.andExpect(jsonPath("$.occurrences[0].issues[0].blocking").value(true))
			.andExpect(jsonPath("$.occurrences[0].issues[0].eventId").value(annasClass))
			.andExpect(jsonPath("$.warnings[0].type").value("STAFF_NOT_QUALIFIED"))
			.andExpect(jsonPath("$.warnings[0].blocking").value(false));
		postAs(ANNA_TOKEN, EVENTS + "/preview", oneOff(GYM, "18:30", "19:30")).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences[0].issues[0].type").value("STAFF_BUSY"))
			.andExpect(jsonPath("$.occurrences[0].issues[0].staffId").value(ANNA.toString()));
		postAs(MAREK_TOKEN, EVENTS + "/preview", oneOff(GYM, "18:30", "19:30")).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(0))
			.andExpect(jsonPath("$.occurrences[0].issues").value(empty()));

		listOwn(MAREK_TOKEN).andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void aConflictRejectsTheClass() throws Exception {
		createEvent(poleClass(this.monday, "18:00", "19:00"));

		postAs(MAREK_TOKEN, EVENTS, oneOff(POLE_ROOM, "18:30", "19:30")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("RESOURCE_BUSY"));
		insertTimeOff(MAREK, at(this.monday, "07:00"), at(this.monday, "12:00"), "APPROVED");
		postAs(MAREK_TOKEN, EVENTS, oneOff(GYM, "08:00", "09:00")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("STAFF_ON_LEAVE"));
		postAs(MAREK_TOKEN, EVENTS, oneOff(CLOSED_ROOM, "20:00", "21:00")).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("RESOURCE_UNAVAILABLE"));

		listOwn(MAREK_TOKEN).andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void trainerAddsARecurringClass() throws Exception {
		LocalDate endsOn = this.monday.plusWeeks(4).minusDays(1);

		postAs(ANNA_TOKEN, SERIES, series("[2, 4]", endsOn, "[]")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrenceCount").value(8))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[*].defaultStaffId", everyItem(is(ANNA.toString()))))
			.andExpect(jsonPath("$.items[*].defaultResourceId", everyItem(is(POLE_ROOM.toString()))));

		listOwn(ANNA_TOKEN).andExpect(jsonPath("$.items.length()").value(8))
			.andExpect(jsonPath("$.items[*].kind", everyItem(is("CLASS"))))
			.andExpect(jsonPath("$.items[*].visibility", everyItem(is("PUBLIC"))));
	}

	@Test
	void seriesPreviewNamesTheConflictingDatesThatCanBeExcluded() throws Exception {
		LocalDate busyTuesday = this.monday.plusWeeks(1).plusDays(1);
		createEvent(eventJson("CLASS", POLE, at(busyTuesday, "18:00"), at(busyTuesday, "19:00"), staff(MAREK, "LEAD"),
				room(POLE_ROOM, true)));
		LocalDate endsOn = this.monday.plusWeeks(3).minusDays(1);

		postAs(ANNA_TOKEN, SERIES + "/preview", series("[2]", endsOn, "[]")).andExpect(status().isOk())
			.andExpect(jsonPath("$.occurrences.length()").value(3))
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences[1].date").value(busyTuesday.toString()))
			.andExpect(jsonPath("$.occurrences[1].issues[0].type").value("RESOURCE_BUSY"));
		postAs(ANNA_TOKEN, SERIES, series("[2]", endsOn, "[]")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.conflicts[0].date").value(busyTuesday.toString()));

		postAs(ANNA_TOKEN, SERIES, series("[2]", endsOn, "[\"" + busyTuesday + "\"]")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrenceCount").value(2));
	}

	@Test
	void planningIsForTrainersWithAStaffProfile() throws Exception {
		String oneOff = oneOff(GYM, "18:00", "19:00");
		String series = series("[2]", this.monday.plusWeeks(1), "[]");
		for (String path : new String[] { EVENTS, EVENTS + "/preview", SERIES, SERIES + "/preview" }) {
			String body = path.startsWith(SERIES) ? series : oneOff;
			this.anonymous.perform(json(MockMvcRequestBuilders.post(path), body)).andExpect(status().isUnauthorized());
			post(path, body).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
			postAs(TRAINER_TOKEN, path, body).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		postAs(ANNA_TOKEN, EVENTS, "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void trainersCanListRoomsButNotChangeThem() throws Exception {
		this.mockMvc.perform(as(ANNA_TOKEN, MockMvcRequestBuilders.get("/api/v1/resources").param("isActive", "true")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].name").value(contains("Gym", "Pole room")));
		postAs(ANNA_TOKEN, "/api/v1/resources", """
				{"name": "Trainer room", "kind": "ROOM", "maxPeople": 5}
				""").andExpect(status().isForbidden());
		this.mockMvc.perform(as(ANNA_TOKEN, MockMvcRequestBuilders.get("/api/v1/resources/" + GYM)))
			.andExpect(status().isForbidden());
	}

	/** A one-off pole class on {@link #monday} in the given room, without staff. */
	private String oneOff(UUID roomId, String start, String end) {
		Instant startsAt = at(this.monday, start);
		Instant endsAt = at(this.monday, end);
		return """
				{"serviceTypeId": "%s", "startsAt": "%s", "endsAt": "%s", "resources": [%s]}
				""".formatted(POLE, startsAt, endsAt, room(roomId, true));
	}

	/** A pole class at 18:00 in the pole room from {@link #monday} until {@code endsOn}. */
	private String series(String weekdays, LocalDate endsOn, String excludedDates) {
		return """
				{"serviceTypeId": "%s", "weekdays": %s, "localStartTime": "18:00", "durationMinutes": 60,
				 "startsOn": "%s", "endsOn": "%s", "excludedDates": %s, "resources": [%s]}
				""".formatted(POLE, weekdays, this.monday, endsOn, excludedDates, room(POLE_ROOM, true));
	}

	private ResultActions postAs(String token, String path, String body) throws Exception {
		return this.mockMvc.perform(as(token, json(MockMvcRequestBuilders.post(path), body)));
	}

	private ResultActions listOwn(String token) throws Exception {
		return this.mockMvc
			.perform(as(token, MockMvcRequestBuilders.get(EVENTS)
				.param("from", at(this.monday, "00:00").toString())
				.param("to", at(this.monday.plusWeeks(5), "00:00").toString())))
			.andExpect(status().isOk());
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
