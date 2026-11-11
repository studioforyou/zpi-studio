package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Changing one occurrence of a series, or one one-off event, without changing the rest of the
 * series: time, title, capacity, staff and rooms, the checks that apply, the booked places and
 * bringing an occurrence back to its series.
 */
class OccurrenceChangeTest extends ScheduleTestSupport {

	private static final String CUSTOMER_PREFIX = "Occurrence-change";

	@Autowired
	private GroupClassSchedule groupClasses;

	@AfterEach
	void removeReservations() {
		this.jdbcTemplate.update("DELETE FROM reservation WHERE customer_id IN "
				+ "(SELECT id FROM customer_profile WHERE first_name = ?)", CUSTOMER_PREFIX);
		this.jdbcTemplate.update("DELETE FROM customer_profile WHERE first_name = ?", CUSTOMER_PREFIX);
	}

	@Test
	void changesOneOccurrenceOfASeriesWithoutTheRestOfTheSeries() throws Exception {
		String seriesId = createTuesdaySeries(3);
		List<String> ids = occurrenceIds();
		String second = ids.get(1);
		LocalDate secondDay = this.monday.plusWeeks(1).plusDays(1);

		put("/api/v1/schedule-events/" + second, change(0, at(secondDay.plusDays(1), "19:00"),
				at(secondDay.plusDays(1), "20:30"), "\"Pole dance - extra\"", "8", staff(ANNA, "LEAD"),
				room(GYM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(at(secondDay.plusDays(1), "19:00").toString()))
			.andExpect(jsonPath("$.endsAt").value(at(secondDay.plusDays(1), "20:30").toString()))
			.andExpect(jsonPath("$.seriesChange.usualStartsAt").value(at(secondDay, "18:00").toString()))
			.andExpect(jsonPath("$.title").value("Pole dance - extra"))
			.andExpect(jsonPath("$.capacity").value(8))
			.andExpect(jsonPath("$.resources[0].resourceId").value(GYM.toString()))
			.andExpect(jsonPath("$.seriesId").value(seriesId))
			.andExpect(jsonPath("$.isSeriesException").value(true))
			.andExpect(jsonPath("$.version").value(1));

		// The other occurrences and the series keep the rule.
		for (String other : List.of(ids.get(0), ids.get(2))) {
			get("/api/v1/schedule-events/" + other).andExpect(status().isOk())
				.andExpect(jsonPath("$.isSeriesException").value(false))
				.andExpect(jsonPath("$.title").value("Pole dance"))
				.andExpect(jsonPath("$.capacity").value(10))
				.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()));
		}
		get("/api/v1/schedule-events/" + ids.get(0))
			.andExpect(jsonPath("$.startsAt").value(at(this.monday.plusDays(1), "18:00").toString()))
			.andExpect(jsonPath("$.seriesChange").doesNotExist());
		get("/api/v1/recurrence-series/" + seriesId).andExpect(status().isOk())
			.andExpect(jsonPath("$.localStartTime").value("18:00"))
			.andExpect(jsonPath("$.defaultResourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.occurrenceCount").value(3));
	}

	@Test
	void aRequestThatChangesNothingKeepsTheOccurrenceInTheSeries() throws Exception {
		createTuesdaySeries(1);
		String id = occurrenceIds().get(0);
		LocalDate tuesday = this.monday.plusDays(1);

		put("/api/v1/schedule-events/" + id, change(0, at(tuesday, "18:00"), at(tuesday, "19:00"), "null", "10",
				staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Pole dance"))
			.andExpect(jsonPath("$.isSeriesException").value(false))
			.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void aOneOffEventCanBeChangedAndHasNoSeriesChange() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));

		put("/api/v1/schedule-events/" + id, change(0, at(this.monday, "17:00"), at(this.monday, "18:30"), "null",
				"null", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(at(this.monday, "17:00").toString()))
			.andExpect(jsonPath("$.capacity").doesNotExist())
			.andExpect(jsonPath("$.seriesChange").doesNotExist())
			.andExpect(jsonPath("$.isSeriesException").value(false));
	}

	@Test
	void aBlockKeepsItsRulesWhenItIsMoved() throws Exception {
		String block = createEvent("""
				{"kind": "BLOCK", "title": "Maintenance", "startsAt": "%s", "endsAt": "%s",
				 "staff": [], "resources": [%s]}
				""".formatted(at(this.monday, "08:00"), at(this.monday, "10:00"), room(GYM, true)));

		put("/api/v1/schedule-events/" + block, change(0, at(this.monday, "09:00"), at(this.monday, "11:00"), "null",
				"5", staff(ANNA, "LEAD"), room(GYM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		put("/api/v1/schedule-events/" + block, change(0, at(this.monday, "09:00"), at(this.monday, "11:00"), "null",
				"5", "", room(GYM, false)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("BLOCK"))
			.andExpect(jsonPath("$.title").value("Maintenance"))
			.andExpect(jsonPath("$.startsAt").value(at(this.monday, "09:00").toString()))
			.andExpect(jsonPath("$.capacity").doesNotExist())
			.andExpect(jsonPath("$.staff.length()").value(0))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(true));
	}

	@Test
	void checksConflictsAtTheNewTimeButNotWithTheEventItself() throws Exception {
		createTuesdaySeries(1);
		String id = occurrenceIds().get(0);
		LocalDate tuesday = this.monday.plusDays(1);
		String gymClass = createEvent(eventJson("CLASS", POLE, at(tuesday, "20:00"), at(tuesday, "21:00"),
				staff(MAREK, "LEAD"), room(GYM, true)));

		put("/api/v1/schedule-events/" + id, change(0, at(tuesday, "19:30"), at(tuesday, "20:30"), "null", "10",
				staff(ANNA, "LEAD"), room(GYM, true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].eventId").value(gymClass));

		put("/api/v1/schedule-events/" + id, change(0, at(tuesday, "20:00"), at(tuesday, "21:00"), "null", "10",
				staff(MAREK, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"));

		// Overlapping its own previous time is fine.
		put("/api/v1/schedule-events/" + id, change(0, at(tuesday, "18:30"), at(tuesday, "19:30"), "null", "10",
				staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isSeriesException").value(true));
	}

	@Test
	void previewListsProblemsAtTheNewTimeWithoutSaving() throws Exception {
		createTuesdaySeries(1);
		String id = occurrenceIds().get(0);
		LocalDate tuesday = this.monday.plusDays(1);
		insertTimeOff(ANNA, at(tuesday, "19:00"), at(tuesday, "22:00"), "APPROVED");

		post("/api/v1/schedule-events/" + id + "/change-preview", change(0, at(tuesday, "19:00"),
				at(tuesday, "20:00"), "null", "10", staff(ANNA, "LEAD") + ", " + staff(MAREK, "ASSISTANT"),
				room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.occurrences.length()").value(1))
			.andExpect(jsonPath("$.occurrences[0].issues[0].type").value("STAFF_ON_LEAVE"))
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.warnings[0].type").value("STAFF_NOT_QUALIFIED"));

		post("/api/v1/schedule-events/" + id + "/change-preview", change(0, at(tuesday, "18:15"),
				at(tuesday, "19:00"), "null", "10", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(0));

		get("/api/v1/schedule-events/" + id)
			.andExpect(jsonPath("$.startsAt").value(at(tuesday, "18:00").toString()))
			.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void theCapacityCannotDropBelowTheBookedPlaces() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		book(id, at(this.monday, "06:00"));
		book(id, at(this.monday, "06:00"));

		put("/api/v1/schedule-events/" + id, change(0, at(this.monday, "18:00"), at(this.monday, "19:00"), "null",
				"1", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		put("/api/v1/schedule-events/" + id, change(0, at(this.monday, "18:00"), at(this.monday, "19:00"), "null",
				"2", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capacity").value(2));
	}

	@Test
	void freeCancellationDeadlinesFollowAMovedEvent() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		UUID reservation = book(id, at(this.monday, "06:00"));

		// One day later: the deadline moves by a day as well.
		put("/api/v1/schedule-events/" + id, change(0, at(this.monday.plusDays(1), "18:00"),
				at(this.monday.plusDays(1), "19:00"), "null", "10", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk());
		assertThat(freeCancelUntil(reservation)).isEqualTo(at(this.monday.plusDays(1), "06:00"));

		// Moved earlier, a client keeps the deadline they had while it is before the new start.
		put("/api/v1/schedule-events/" + id, change(1, at(this.monday.plusDays(1), "09:00"),
				at(this.monday.plusDays(1), "10:00"), "null", "10", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk());
		assertThat(freeCancelUntil(reservation)).isEqualTo(at(this.monday.plusDays(1), "06:00"));

		// ... and at most until the new start.
		put("/api/v1/schedule-events/" + id, change(2, at(this.monday.plusDays(1), "05:00"),
				at(this.monday.plusDays(1), "06:00"), "null", "10", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk());
		assertThat(freeCancelUntil(reservation)).isEqualTo(at(this.monday.plusDays(1), "05:00"));
	}

	@Test
	void rejectsStaleVersionsInvalidTimesAndEventsThatCannotChange() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String valid = change(0, at(this.monday, "19:00"), at(this.monday, "20:00"), "null", "10",
				staff(ANNA, "LEAD"), room(POLE_ROOM, true));

		put("/api/v1/schedule-events/" + id, change(3, at(this.monday, "19:00"), at(this.monday, "20:00"), "null",
				"10", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		put("/api/v1/schedule-events/" + id, change(0, at(this.monday, "19:00"), at(this.monday, "18:00"), "null",
				"10", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		Instant past = Instant.now().minus(Duration.ofDays(1));
		put("/api/v1/schedule-events/" + id, change(0, past, past.plus(Duration.ofHours(1)), "null", "10",
				staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		put("/api/v1/schedule-events/" + id, change(0, at(this.monday, "19:00"), at(this.monday, "20:00"), "null",
				"20", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		this.jdbcTemplate.update("UPDATE schedule_event SET starts_at = ?, ends_at = ? WHERE id = ?",
				Timestamp.from(past), Timestamp.from(past.plus(Duration.ofHours(1))), UUID.fromString(id));
		put("/api/v1/schedule-events/" + id, valid).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

		String cancelled = createEvent(poleClass(this.monday.plusDays(2), "18:00", "19:00"));
		setStatus(cancelled, "CANCELLED");
		put("/api/v1/schedule-events/" + cancelled, valid).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
		put("/api/v1/schedule-events/" + UUID.randomUUID(), valid).andExpect(status().isNotFound());
	}

	@Test
	void restoresAChangedOccurrenceToItsSeries() throws Exception {
		createTuesdaySeries(3);
		List<String> ids = occurrenceIds();
		String second = ids.get(1);
		LocalDate secondDay = this.monday.plusWeeks(1).plusDays(1);
		put("/api/v1/schedule-events/" + second, change(0, at(secondDay, "20:00"), at(secondDay, "20:45"),
				"\"Moved class\"", "5", staff(MAREK, "LEAD"), room(GYM, false)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isSeriesException").value(true));

		post("/api/v1/schedule-events/" + second + "/restore-series", "{\"version\": 0}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		post("/api/v1/schedule-events/" + second + "/restore-series", "{\"version\": 1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(at(secondDay, "18:00").toString()))
			.andExpect(jsonPath("$.endsAt").value(at(secondDay, "19:00").toString()))
			.andExpect(jsonPath("$.title").value("Pole dance"))
			.andExpect(jsonPath("$.capacity").value(10))
			.andExpect(jsonPath("$.staff.length()").value(1))
			.andExpect(jsonPath("$.staff[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(true))
			.andExpect(jsonPath("$.isSeriesException").value(false))
			.andExpect(jsonPath("$.version").value(2));

		// Restoring an occurrence that follows the rule changes nothing.
		post("/api/v1/schedule-events/" + second + "/restore-series", "{\"version\": 2}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2));
		String oneOff = createEvent(poleClass(this.monday.plusDays(3), "10:00", "11:00"));
		post("/api/v1/schedule-events/" + oneOff + "/restore-series", "{\"version\": 0}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
	}

	@Test
	void restoringChecksThatTheSeriesTimeIsStillFree() throws Exception {
		createTuesdaySeries(1);
		String id = occurrenceIds().get(0);
		LocalDate tuesday = this.monday.plusDays(1);
		put("/api/v1/schedule-events/" + id, change(0, at(tuesday, "20:00"), at(tuesday, "21:00"), "null", "10",
				staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk());
		createEvent(eventJson("CLASS", POLE, at(tuesday, "18:00"), at(tuesday, "19:00"), staff(MAREK, "LEAD"),
				room(POLE_ROOM, true)));

		post("/api/v1/schedule-events/" + id + "/restore-series", "{\"version\": 1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"));
	}

	@Test
	void changingAnEventIsForAdministratorsOnly() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String body = change(0, at(this.monday, "19:00"), at(this.monday, "20:00"), "null", "10",
				staff(ANNA, "LEAD"), room(POLE_ROOM, true));

		this.mockMvc
			.perform(asTrainer(MockMvcRequestBuilders.put("/api/v1/schedule-events/" + id)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)))
			.andExpect(status().isForbidden());
		this.anonymous
			.perform(MockMvcRequestBuilders.post("/api/v1/schedule-events/" + id + "/restore-series")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"version\": 0}"))
			.andExpect(status().isUnauthorized());
	}

	/** A weekly pole class on Tuesdays at 18:00 for the given number of weeks, run by Anna in the pole room. */
	@Test
	void showsWhatAChangeChangedAgainstTheSeries() throws Exception {
		createTuesdaySeries(3);
		List<String> ids = occurrenceIds();
		LocalDate secondDay = this.monday.plusWeeks(1).plusDays(1);

		put("/api/v1/schedule-events/" + ids.get(1), change(0, at(secondDay.plusDays(1), "19:00"),
				at(secondDay.plusDays(1), "20:30"), "null", "10", staff(MAREK, "LEAD"), room(GYM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.seriesChange.usualStartsAt").value(at(secondDay, "18:00").toString()))
			.andExpect(jsonPath("$.seriesChange.usualEndsAt").value(at(secondDay, "19:00").toString()))
			.andExpect(jsonPath("$.seriesChange.usualInstructors[0]").value("Anna Kowalska"))
			.andExpect(jsonPath("$.seriesChange.usualRooms[0]").value("Pole room"));
		get("/api/v1/schedule-events/" + ids.get(0)).andExpect(jsonPath("$.seriesChange").doesNotExist());

		// Only what participants see counts: a new capacity leaves no visible change.
		put("/api/v1/schedule-events/" + ids.get(2), change(0, at(secondDay.plusWeeks(1), "18:00"),
				at(secondDay.plusWeeks(1), "19:00"), "null", "6", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isSeriesException").value(true))
			.andExpect(jsonPath("$.seriesChange").doesNotExist());

		// Only the room: the time and the lead are as usual.
		post("/api/v1/schedule-events/" + ids.get(1) + "/restore-series", "{\"version\": 1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.seriesChange").doesNotExist());
		put("/api/v1/schedule-events/" + ids.get(1), change(2, at(secondDay, "18:00"), at(secondDay, "19:00"),
				"null", "10", staff(ANNA, "LEAD"), room(GYM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.seriesChange.usualStartsAt").doesNotExist())
			.andExpect(jsonPath("$.seriesChange.usualInstructors").doesNotExist())
			.andExpect(jsonPath("$.seriesChange.usualRooms[0]").value("Pole room"));
	}

	@Test
	void participantsAndTrainersSeeAnOccurrenceAtItsUsualTimeAndWhereItWent() throws Exception {
		createTuesdaySeries(2);
		List<String> ids = occurrenceIds();
		LocalDate firstTuesday = this.monday.plusDays(1);
		LocalDate nextMonday = this.monday.plusWeeks(1);
		Instant weekStart = at(this.monday, "00:00");
		Instant weekEnd = at(nextMonday, "00:00");
		// The first occurrence moves to the next week; Marek runs the second one instead of Anna.
		put("/api/v1/schedule-events/" + ids.get(0), change(0, at(nextMonday, "18:00"), at(nextMonday, "19:00"),
				"null", "10", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk());
		put("/api/v1/schedule-events/" + ids.get(1), change(0, at(nextMonday.plusDays(1), "18:00"),
				at(nextMonday.plusDays(1), "19:00"), "null", "10", staff(MAREK, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk());

		// The customer offer of the first week holds the moved class, so its usual slot can show where it went.
		assertThat(this.groupClasses.list(weekStart, weekEnd)).singleElement().satisfies((event) -> {
			assertThat(event.id()).hasToString(ids.get(0));
			assertThat(event.startsAt()).isEqualTo(at(nextMonday, "18:00"));
			assertThat(event.seriesChange().usualStartsAt()).isEqualTo(at(firstTuesday, "18:00"));
		});
		// The administrator's timetable stays a plain window.
		get("/api/v1/schedule-events", "from", weekStart.toString(), "to", weekEnd.toString())
			.andExpect(jsonPath("$.items.length()").value(0));

		// Anna sees her moved class in its usual week and the class Marek runs for her once.
		this.mockMvc.perform(as(ANNA_TOKEN, MockMvcRequestBuilders.get("/api/v1/me/schedule-events")
				.param("from", weekStart.toString()).param("to", weekEnd.toString())))
			.andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(ids.get(0))));
		this.mockMvc.perform(as(ANNA_TOKEN, MockMvcRequestBuilders.get("/api/v1/me/schedule-events")
				.param("from", weekEnd.toString()).param("to", at(nextMonday.plusWeeks(1), "00:00").toString())))
			.andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(ids.get(0), ids.get(1))))
			.andExpect(jsonPath("$.items[1].staff[0].staffId").value(MAREK.toString()))
			.andExpect(jsonPath("$.items[1].seriesChange.usualInstructors[0]").value("Anna Kowalska"));
		// Marek sees the class he runs, with whom he stands in for.
		this.mockMvc.perform(as(MAREK_TOKEN, MockMvcRequestBuilders.get("/api/v1/me/schedule-events")
				.param("from", weekEnd.toString()).param("to", at(nextMonday.plusWeeks(1), "00:00").toString())))
			.andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(ids.get(1))))
			.andExpect(jsonPath("$.items[0].seriesChange.usualInstructors[0]").value("Anna Kowalska"));
	}

	@Test
	void everyChangeTellsEveryoneItConcernsAgain() throws Exception {
		createTuesdaySeries(1);
		String id = occurrenceIds().get(0);
		LocalDate tuesday = this.monday.plusDays(1);
		UUID reservation = book(id, at(tuesday, "06:00"));
		UUID customer = this.jdbcTemplate.queryForObject("SELECT customer_id FROM reservation WHERE id = ?",
				UUID.class, reservation);

		// Marek takes the class over: Anna is taken off, Marek assigned, the participant told.
		put("/api/v1/schedule-events/" + id + "/assignments", """
				{"version": 0, "scope": "THIS_EVENT", "staff": [{"staffId": "%s", "role": "LEAD"}],
				 "resources": [{"resourceId": "%s"}]}
				""".formatted(MAREK, POLE_ROOM))
			.andExpect(status().isOk());
		staffChanges(MAREK_TOKEN).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("ASSIGNED"))
			.andExpect(jsonPath("$.items[0].previousLeads[0]").value("Anna Kowalska"))
			.andExpect(jsonPath("$.items[0].leads[0]").value("Marek Nowak"));
		staffChanges(ANNA_TOKEN).andExpect(jsonPath("$.items[0].kind").value("UNASSIGNED"));
		assertThat(customerNotices(customer)).containsExactly("CHANGED");

		// Marek closes his notice; a later move of the class tells him, and everyone else, again.
		String notice = JsonPath.read(staffChanges(MAREK_TOKEN).andReturn().getResponse().getContentAsString(),
				"$.items[0].id");
		this.mockMvc.perform(as(MAREK_TOKEN,
				MockMvcRequestBuilders.post("/api/v1/me/schedule-changes/" + notice + "/dismiss")))
			.andExpect(status().isNoContent());
		staffChanges(MAREK_TOKEN).andExpect(jsonPath("$.items.length()").value(0));

		put("/api/v1/schedule-events/" + id, change(version(id), at(tuesday, "19:00"), at(tuesday, "20:00"), "null", "10",
				staff(MAREK, "LEAD"), room(GYM, true)))
			.andExpect(status().isOk());
		staffChanges(MAREK_TOKEN).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("CHANGED"))
			.andExpect(jsonPath("$.items[0].previousStartsAt").value(at(tuesday, "18:00").toString()))
			.andExpect(jsonPath("$.items[0].startsAt").value(at(tuesday, "19:00").toString()))
			.andExpect(jsonPath("$.items[0].previousRooms[0]").value("Pole room"))
			.andExpect(jsonPath("$.items[0].rooms[0]").value("Gym"));
		// Anna no longer works at the class, so its move is not hers to know.
		staffChanges(ANNA_TOKEN).andExpect(jsonPath("$.items.length()").value(1));
		assertThat(customerNotices(customer)).containsExactly("CHANGED", "CHANGED");

		// A change of the title only tells nobody.
		put("/api/v1/schedule-events/" + id, change(version(id), at(tuesday, "19:00"), at(tuesday, "20:00"),
				"\"Pole dance - extra\"", "10", staff(MAREK, "LEAD"), room(GYM, true)))
			.andExpect(status().isOk());
		assertThat(customerNotices(customer)).hasSize(2);
	}

	@Test
	void aChangeOfTheFollowingOccurrencesTellsEachPersonOnceAboutTheEarliest() throws Exception {
		createTuesdaySeries(3);
		List<String> ids = occurrenceIds();
		LocalDate tuesday = this.monday.plusDays(1);
		UUID reservation = book(ids.get(1), at(tuesday.plusWeeks(1), "06:00"));
		UUID customer = this.jdbcTemplate.queryForObject("SELECT customer_id FROM reservation WHERE id = ?",
				UUID.class, reservation);
		UUID admin = this.jdbcTemplate.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class,
				"admin" + TEST_EMAIL_DOMAIN);
		this.jdbcTemplate.update("INSERT INTO reservation (event_id, customer_id, booked_by) VALUES (?, ?, ?)",
				UUID.fromString(ids.get(2)), customer, admin);

		put("/api/v1/schedule-events/" + ids.get(0) + "/assignments", """
				{"version": 0, "scope": "THIS_AND_FOLLOWING", "staff": [{"staffId": "%s", "role": "LEAD"}],
				 "resources": [{"resourceId": "%s"}]}
				""".formatted(MAREK, POLE_ROOM))
			.andExpect(status().isOk());

		staffChanges(MAREK_TOKEN).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("ASSIGNED"))
			.andExpect(jsonPath("$.items[0].eventId").value(ids.get(0)));
		staffChanges(ANNA_TOKEN).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("UNASSIGNED"));
		assertThat(this.jdbcTemplate.queryForList(
				"SELECT event_id FROM schedule_change_notice WHERE customer_id = ?", UUID.class, customer))
			.containsExactly(UUID.fromString(ids.get(1)));
	}

	private ResultActions staffChanges(String token) throws Exception {
		return this.mockMvc.perform(as(token, MockMvcRequestBuilders.get("/api/v1/me/schedule-changes")))
			.andExpect(status().isOk());
	}

	private int version(String eventId) throws Exception {
		return JsonPath.read(get("/api/v1/schedule-events/" + eventId).andReturn().getResponse().getContentAsString(),
				"$.version");
	}

	private List<String> customerNotices(UUID customer) {
		return this.jdbcTemplate.queryForList(
				"SELECT kind FROM schedule_change_notice WHERE customer_id = ? ORDER BY created_at, id", String.class,
				customer);
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

	private static String change(int version, Instant startsAt, Instant endsAt, String title, String capacity,
			String staff, String resources) {
		return """
				{"version": %d, "startsAt": "%s", "endsAt": "%s", "title": %s, "capacity": %s,
				 "staff": [%s], "resources": [%s]}
				""".formatted(version, startsAt, endsAt, title, capacity, staff, resources);
	}

	/** An active reservation of a new customer with the given free cancellation deadline. */
	private UUID book(String eventId, Instant freeCancelUntil) {
		UUID customer = this.jdbcTemplate.queryForObject(
				"INSERT INTO customer_profile (first_name, last_name) VALUES (?, ?) RETURNING id", UUID.class,
				CUSTOMER_PREFIX, UUID.randomUUID().toString().substring(0, 8));
		UUID admin = this.jdbcTemplate.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class,
				"admin" + TEST_EMAIL_DOMAIN);
		return this.jdbcTemplate.queryForObject("""
				INSERT INTO reservation (event_id, customer_id, booked_by, free_cancel_until)
				VALUES (?, ?, ?, ?) RETURNING id
				""", UUID.class, UUID.fromString(eventId), customer, admin, Timestamp.from(freeCancelUntil));
	}

	private Instant freeCancelUntil(UUID reservation) {
		return this.jdbcTemplate
			.queryForObject("SELECT free_cancel_until FROM reservation WHERE id = ?", Timestamp.class, reservation)
			.toInstant();
	}

}
