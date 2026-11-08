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
			.andExpect(jsonPath("$.originalStartsAt").value(at(secondDay, "18:00").toString()))
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
			.andExpect(jsonPath("$.originalStartsAt").value(at(this.monday.plusDays(1), "18:00").toString()));
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
	void aOneOffEventCanBeChangedAndHasNoOriginalStart() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));

		put("/api/v1/schedule-events/" + id, change(0, at(this.monday, "17:00"), at(this.monday, "18:30"), "null",
				"null", staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(at(this.monday, "17:00").toString()))
			.andExpect(jsonPath("$.capacity").doesNotExist())
			.andExpect(jsonPath("$.originalStartsAt").doesNotExist())
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
