package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.everyItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.ResultActions;

/**
 * Recurring events: generating occurrences in the studio's local time, excluded dates,
 * conflicts of a whole series and changing the staff of following occurrences.
 */
class RecurrenceSeriesControllerTest extends ScheduleTestSupport {

	@Test
	void createsOneSeriesPerWeekdayWithAllOccurrences() throws Exception {
		ResultActions created = post("/api/v1/recurrence-series", series("[2, 4]", this.monday,
				this.monday.plusWeeks(4).minusDays(1), 1, "[]"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrenceCount").value(8))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].weekday").value(2))
			.andExpect(jsonPath("$.items[0].localStartTime").value("18:00"))
			.andExpect(jsonPath("$.items[0].durationMinutes").value(60))
			.andExpect(jsonPath("$.items[0].startsOn").value(this.monday.plusDays(1).toString()))
			.andExpect(jsonPath("$.items[0].endsOn").value(this.monday.plusWeeks(4).minusDays(1).toString()))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$.items[0].defaultStaffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.items[0].defaultResourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.items[0].occurrenceCount").value(4))
			.andExpect(jsonPath("$.items[1].weekday").value(4));
		String tuesdays = JsonPath.read(body(created), "$.items[0].id");
		created.andExpect(header().string("Location", endsWith("/api/v1/recurrence-series/" + tuesdays)));

		get("/api/v1/recurrence-series/" + tuesdays).andExpect(status().isOk())
			.andExpect(jsonPath("$.occurrenceCount").value(4));
		get("/api/v1/schedule-events", "from", at(this.monday, "00:00").toString(), "to",
				at(this.monday.plusWeeks(4), "00:00").toString(), "seriesId", tuesdays)
			.andExpect(jsonPath("$.items.length()").value(4))
			.andExpect(jsonPath("$.items[0].startsAt").value(at(this.monday.plusDays(1), "18:00").toString()))
			.andExpect(jsonPath("$.items[3].startsAt").value(at(this.monday.plusDays(22), "18:00").toString()))
			.andExpect(jsonPath("$.items[*].kind", everyItem(org.hamcrest.Matchers.is("CLASS"))))
			.andExpect(jsonPath("$.items[*].seriesId", everyItem(org.hamcrest.Matchers.is(tuesdays))))
			.andExpect(jsonPath("$.items[0].staff[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.items[0].resources[0].resourceId").value(POLE_ROOM.toString()));
		get("/api/v1/recurrence-series/" + java.util.UUID.randomUUID()).andExpect(status().isNotFound());
	}

	@Test
	void keepsTheLocalTimeAcrossADaylightSavingTimeChange() throws Exception {
		LocalDate transitionDay = nextTransitionDayAfter(LocalDate.now(STUDIO_ZONE).plusDays(8));
		LocalDate startsOn = transitionDay.minusDays(7);
		int weekday = startsOn.getDayOfWeek().getValue();

		post("/api/v1/recurrence-series", series("[" + weekday + "]", startsOn, startsOn.plusWeeks(2), 1, "[]"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrenceCount").value(3));

		String json = body(get("/api/v1/schedule-events", "from", at(startsOn, "00:00").toString(), "to",
				at(startsOn.plusWeeks(3), "00:00").toString()));
		List<String> starts = JsonPath.read(json, "$.items[*].startsAt");
		assertThat(starts).hasSize(3);
		assertThat(starts).allSatisfy((start) -> assertThat(Instant.parse(start).atZone(STUDIO_ZONE).toLocalTime())
			.isEqualTo(LocalTime.of(18, 0)));
		assertThat(Instant.parse(starts.get(0)).atZone(java.time.ZoneOffset.UTC).getHour())
			.isNotEqualTo(Instant.parse(starts.get(2)).atZone(java.time.ZoneOffset.UTC).getHour());
	}

	@Test
	void skipsExcludedDatesAndRespectsTheInterval() throws Exception {
		LocalDate secondOccurrence = this.monday.plusWeeks(2);
		String request = series("[1]", this.monday, this.monday.plusWeeks(5), 2, "[\"" + secondOccurrence + "\"]");

		post("/api/v1/recurrence-series/preview", request).andExpect(status().isOk())
			.andExpect(jsonPath("$.occurrences.length()").value(3))
			.andExpect(jsonPath("$.occurrences[0].date").value(this.monday.toString()))
			.andExpect(jsonPath("$.occurrences[1].date").value(secondOccurrence.toString()))
			.andExpect(jsonPath("$.occurrences[1].excluded").value(true))
			.andExpect(jsonPath("$.occurrences[2].date").value(this.monday.plusWeeks(4).toString()))
			.andExpect(jsonPath("$.conflictCount").value(0));
		post("/api/v1/recurrence-series", request).andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrenceCount").value(2))
			.andExpect(jsonPath("$.items[0].intervalWeeks").value(2));
	}

	@Test
	void aConflictRejectsTheWholeSeriesUntilItsDateIsExcluded() throws Exception {
		LocalDate busyTuesday = this.monday.plusWeeks(1).plusDays(1);
		String busy = createEvent(eventJson("WORKSHOP", POLE, at(busyTuesday, "18:30"), at(busyTuesday, "20:00"),
				staff(MAREK, "LEAD"), room(POLE_ROOM, true)));
		LocalDate endsOn = this.monday.plusWeeks(3);

		post("/api/v1/recurrence-series/preview", series("[2]", this.monday, endsOn, 1, "[]")).andExpect(status().isOk())
			.andExpect(jsonPath("$.occurrences.length()").value(3))
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences[1].issues[0].type").value("RESOURCE_BUSY"))
			.andExpect(jsonPath("$.occurrences[1].issues[0].eventId").value(busy));
		post("/api/v1/recurrence-series", series("[2]", this.monday, endsOn, 1, "[]"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"))
			.andExpect(jsonPath("$.conflicts.length()").value(1))
			.andExpect(jsonPath("$.conflicts[0].date").value(busyTuesday.toString()));
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM recurrence_series", Long.class)).isZero();

		post("/api/v1/recurrence-series", series("[2]", this.monday, endsOn, 1, "[\"" + busyTuesday + "\"]"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.occurrenceCount").value(2));
	}

	@Test
	void rejectsInvalidSeries() throws Exception {
		LocalDate today = LocalDate.now(STUDIO_ZONE);
		post("/api/v1/recurrence-series", series("[1]", today.minusDays(1), today.plusWeeks(2), 1, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startsOn"));
		post("/api/v1/recurrence-series", series("[1]", this.monday, this.monday.minusDays(1), 1, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endsOn"));
		post("/api/v1/recurrence-series", series("[1]", this.monday, this.monday.plusYears(1).plusDays(1), 1, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endsOn"));
		post("/api/v1/recurrence-series", series("[3]", this.monday, this.monday.plusDays(1), 1, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("weekdays"));
		post("/api/v1/recurrence-series", series("[8]", this.monday, this.monday.plusWeeks(1), 1, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		post("/api/v1/recurrence-series", series("[]", this.monday, this.monday.plusWeeks(1), 1, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("weekdays"));
		post("/api/v1/recurrence-series", series("[1]", this.monday, this.monday.plusWeeks(1), 9, "[]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("intervalWeeks"));
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM schedule_event", Long.class)).isZero();
	}

	@Test
	void changingOneOccurrenceMakesItAnException() throws Exception {
		post("/api/v1/recurrence-series", series("[1]", this.monday, this.monday.plusWeeks(1), 1, "[]"))
			.andExpect(status().isCreated());
		List<String> ids = occurrenceIds();

		put("/api/v1/schedule-events/" + ids.get(0) + "/assignments", """
				{"version": 0, "scope": "THIS_EVENT", "staff": [{"staffId": "%s"}],
				 "resources": [{"resourceId": "%s"}]}
				""".formatted(MAREK, GYM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isSeriesException").value(true))
			.andExpect(jsonPath("$.staff[0].staffId").value(MAREK.toString()));
		get("/api/v1/schedule-events/" + ids.get(1)).andExpect(jsonPath("$.isSeriesException").value(false))
			.andExpect(jsonPath("$.staff[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()));
	}

	@Test
	void changingFollowingOccurrencesUpdatesTheSeriesDefaults() throws Exception {
		String seriesId = JsonPath.read(body(post("/api/v1/recurrence-series",
				series("[1]", this.monday, this.monday.plusWeeks(2), 1, "[]"))
			.andExpect(status().isCreated())), "$.items[0].id");
		List<String> ids = occurrenceIds();
		assertThat(ids).hasSize(3);

		put("/api/v1/schedule-events/" + ids.get(1) + "/assignments", """
				{"version": 0, "scope": "THIS_AND_FOLLOWING",
				 "staff": [{"staffId": "%s", "role": "LEAD"}, {"staffId": "%s", "role": "ASSISTANT"}],
				 "resources": [{"resourceId": "%s"}]}
				""".formatted(MAREK, ANNA, GYM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.isSeriesException").value(false))
			.andExpect(jsonPath("$.staff.length()").value(2));

		get("/api/v1/schedule-events/" + ids.get(0)).andExpect(jsonPath("$.staff.length()").value(1))
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()));
		get("/api/v1/schedule-events/" + ids.get(2)).andExpect(jsonPath("$.staff.length()").value(2))
			.andExpect(jsonPath("$.staff[0].staffId").value(MAREK.toString()))
			.andExpect(jsonPath("$.resources[0].resourceId").value(GYM.toString()))
			.andExpect(jsonPath("$.isSeriesException").value(false));
		get("/api/v1/recurrence-series/" + seriesId).andExpect(jsonPath("$.defaultStaffId").value(MAREK.toString()))
			.andExpect(jsonPath("$.defaultResourceId").value(GYM.toString()));
	}

	@Test
	void changingFollowingOccurrencesIsRejectedWhenOneOfThemConflicts() throws Exception {
		post("/api/v1/recurrence-series", series("[1]", this.monday, this.monday.plusWeeks(2), 1, "[]"))
			.andExpect(status().isCreated());
		List<String> ids = occurrenceIds();
		LocalDate lastMonday = this.monday.plusWeeks(2);
		createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(lastMonday, "18:15"), at(lastMonday, "18:45"),
				staff(MAREK, "LEAD"), room(GYM, false)));

		put("/api/v1/schedule-events/" + ids.get(0) + "/assignments", """
				{"version": 0, "scope": "THIS_AND_FOLLOWING", "staff": [{"staffId": "%s"}], "resources": []}
				""".formatted(MAREK))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"))
			.andExpect(jsonPath("$.conflicts.length()").value(1))
			.andExpect(jsonPath("$.conflicts[0].date").value(lastMonday.toString()));
		get("/api/v1/schedule-events/" + ids.get(0)).andExpect(jsonPath("$.staff[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.version").value(0));
	}

	/** A weekly pole class at 18:00 for 60 minutes, run by Anna in the pole room. */
	@Test
	void createsARecurringBlockOfARoom() throws Exception {
		// A Wednesday class in the pole room, in the third week of the block's series.
		String wednesdayClass = createEvent(poleClass(this.monday.plusWeeks(2).plusDays(2), "07:30", "08:30"));
		String block = """
				{"kind": "BLOCK", "serviceTypeId": "%s", "title": "Sprzątanie", "capacity": 5, "weekdays": [1, 3],
				 "localStartTime": "08:00", "durationMinutes": 60, "startsOn": "%s", "endsOn": "%s",
				 "excludedDates": %s, "resources": [%s]}
				""";
		LocalDate endsOn = this.monday.plusWeeks(4).minusDays(1);

		post("/api/v1/recurrence-series/preview", block.formatted(POLE, this.monday, endsOn, "[]", room(POLE_ROOM, false)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences[5].issues[0].eventId").value(wednesdayClass));
		LocalDate conflicting = this.monday.plusWeeks(2).plusDays(2);
		post("/api/v1/recurrence-series",
				block.formatted(POLE, this.monday, endsOn, "[\"" + conflicting + "\"]", room(POLE_ROOM, false)))
			.andExpect(status().isCreated())
			.andExpect(header().doesNotExist("Location"))
			.andExpect(jsonPath("$.occurrenceCount").value(7))
			// Recurring blocks need no series: each occurrence is a one-off block.
			.andExpect(jsonPath("$.items.length()").value(0));
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM recurrence_series", Integer.class)).isZero();

		get("/api/v1/schedule-events", "from", at(this.monday, "00:00").toString(), "to",
				at(this.monday.plusWeeks(4), "00:00").toString(), "kind", "BLOCK")
			.andExpect(jsonPath("$.items.length()").value(7))
			.andExpect(jsonPath("$.items[*].title", everyItem(org.hamcrest.Matchers.is("Sprzątanie"))))
			.andExpect(jsonPath("$.items[*].visibility", everyItem(org.hamcrest.Matchers.is("PRIVATE"))))
			.andExpect(jsonPath("$.items[*].blockReason", everyItem(org.hamcrest.Matchers.is(ScheduleEvent.BLOCK_REASON))))
			.andExpect(jsonPath("$.items[*].seriesId", everyItem(org.hamcrest.Matchers.nullValue())))
			.andExpect(jsonPath("$.items[*].resources[0].blocksWholeRoom", everyItem(org.hamcrest.Matchers.is(true))));
		post("/api/v1/schedule-events", poleClass(this.monday, "08:30", "09:30")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"));
	}

	private static String series(String weekdays, LocalDate startsOn, LocalDate endsOn, int intervalWeeks,
			String excludedDates) {
		return """
				{"kind": "CLASS", "serviceTypeId": "%s", "weekdays": %s, "localStartTime": "18:00",
				 "durationMinutes": 60, "intervalWeeks": %d, "startsOn": "%s", "endsOn": "%s",
				 "excludedDates": %s, "staff": [%s], "resources": [%s]}
				""".formatted(POLE, weekdays, intervalWeeks, startsOn, endsOn, excludedDates, staff(ANNA, "LEAD"),
				room(POLE_ROOM, true));
	}

	private List<String> occurrenceIds() throws Exception {
		return JsonPath.read(body(get("/api/v1/schedule-events", "from", at(this.monday, "00:00").toString(), "to",
				at(this.monday.plusWeeks(5), "00:00").toString())), "$.items[*].id");
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	/** The studio local date of the first daylight saving time change on or after {@code notBefore}. */
	private static LocalDate nextTransitionDayAfter(LocalDate notBefore) {
		Instant from = notBefore.atStartOfDay(STUDIO_ZONE).toInstant();
		ZoneOffsetTransition transition = STUDIO_ZONE.getRules().nextTransition(from);
		return LocalDate.ofInstant(transition.getInstant(), STUDIO_ZONE);
	}

}
