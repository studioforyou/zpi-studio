package studioforyou.pl.backend.scheduling.internal;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * One-off events, the conflicts that block them, changing their staff and resources, and the
 * timetable query.
 */
class ScheduleEventControllerTest extends ScheduleTestSupport {

	@Test
	void createsAOneOffEventWithItsStaffAndRoom() throws Exception {
		LocalDate tuesday = this.monday.plusDays(1);
		var created = post("/api/v1/schedule-events", """
				{"kind": "CLASS", "serviceTypeId": "%s", "startsAt": "%s", "endsAt": "%s",
				 "staff": [{"staffId": "%s", "role": "LEAD"}, {"staffId": "%s", "role": "ASSISTANT"}],
				 "resources": [{"resourceId": "%s", "blocksWholeRoom": true}, {"resourceId": "%s", "blocksWholeRoom": false}]}
				""".formatted(POLE, at(tuesday, "18:00"), at(tuesday, "19:00"), ANNA, MAREK, POLE_ROOM, GYM))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("CLASS"))
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.visibility").value("PUBLIC"))
			.andExpect(jsonPath("$.title").value("Pole dance"))
			.andExpect(jsonPath("$.serviceTypeName").value("Pole dance"))
			.andExpect(jsonPath("$.capacity").value(10))
			.andExpect(jsonPath("$.seriesId").doesNotExist())
			.andExpect(jsonPath("$.isSeriesException").value(false))
			.andExpect(jsonPath("$.startsAt").value(at(tuesday, "18:00").toString()))
			.andExpect(jsonPath("$.staff.length()").value(2))
			.andExpect(jsonPath("$.staff[0].displayName").value("Anna Kowalska"))
			.andExpect(jsonPath("$.staff[0].role").value("LEAD"))
			.andExpect(jsonPath("$.staff[1].displayName").value("Marek Nowak"))
			.andExpect(jsonPath("$.staff[1].role").value("ASSISTANT"))
			.andExpect(jsonPath("$.resources.length()").value(2))
			.andExpect(jsonPath("$.resources[0].name").value("Gym"))
			.andExpect(jsonPath("$.resources[0].kind").value("ROOM"))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(false))
			.andExpect(jsonPath("$.resources[1].name").value("Pole room"))
			.andExpect(jsonPath("$.resources[1].blocksWholeRoom").value(true))
			.andExpect(jsonPath("$.version").value(0));
		String id = id(created);
		created.andExpect(header().string("Location", endsWith("/api/v1/schedule-events/" + id)));

		get("/api/v1/schedule-events/" + id).andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Pole dance"))
			.andExpect(jsonPath("$.staff.length()").value(2));
		get("/api/v1/schedule-events", "from", at(this.monday, "00:00").toString(), "to",
				at(this.monday.plusWeeks(1), "00:00").toString())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(id));
	}

	@Test
	void appliesDefaultsOfPersonalTraining() throws Exception {
		LocalDate wednesday = this.monday.plusDays(2);
		post("/api/v1/schedule-events", """
				{"kind": "PERSONAL_TRAINING", "serviceTypeId": "%s", "title": "  PT with Tomasz  ",
				 "startsAt": "%s", "endsAt": "%s", "staff": [{"staffId": "%s"}],
				 "resources": [{"resourceId": "%s", "blocksWholeRoom": false}]}
				""".formatted(PERSONAL, at(wednesday, "17:00"), at(wednesday, "18:00"), MAREK, GYM))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("PT with Tomasz"))
			.andExpect(jsonPath("$.visibility").value("PRIVATE"))
			.andExpect(jsonPath("$.capacity").value(1))
			.andExpect(jsonPath("$.staff[0].role").value("LEAD"))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(false));
	}

	@Test
	void personalTrainingUsesTheCapacityConfiguredInTheServiceCatalogue() throws Exception {
		this.mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/service-types/" + PERSONAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"PERSONAL_TRAINING\",\"minParticipants\":2,\"maxParticipants\":3}"))
			.andExpect(status().isOk());

		post("/api/v1/schedule-events", eventJson("PERSONAL_TRAINING", PERSONAL,
				at(this.monday, "17:00"), at(this.monday, "18:00"), staff(MAREK, "LEAD"), room(GYM, false)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.capacity").value(3))
			.andExpect(jsonPath("$.visibility").value("PRIVATE"));
	}

	@Test
	void defaultCapacityDoesNotExceedTheRoom() throws Exception {
		this.jdbcTemplate.update("UPDATE resource SET max_people = 6 WHERE id = ?", POLE_ROOM);

		post("/api/v1/schedule-events", poleClass(this.monday, "18:00", "19:00")).andExpect(status().isCreated())
			.andExpect(jsonPath("$.capacity").value(6));
	}

	@Test
	void rejectsInvalidEvents() throws Exception {
		LocalDate day = this.monday;
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(day, "19:00"), at(day, "18:00"), "", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("endsAt"));
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(day, "08:00"), at(day.plusDays(2), "08:00"), "", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("endsAt"));
		Instant past = Instant.now().minusSeconds(3600);
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, past, past.plusSeconds(3600), "", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("startsAt"))
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));
		post("/api/v1/schedule-events", eventJson("CONSULTATION", POLE, at(day, "18:00"), at(day, "19:00"), "", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("kind"));
		post("/api/v1/schedule-events", """
				{"kind": "CLASS", "startsAt": "%s", "endsAt": "%s"}
				""".formatted(at(day, "18:00"), at(day, "19:00")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("serviceTypeId"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		post("/api/v1/schedule-events", """
				{"kind": "CLASS", "serviceTypeId": "%s", "startsAt": "%s", "endsAt": "%s", "capacity": 0}
				""".formatted(POLE, at(day, "18:00"), at(day, "19:00")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("capacity"));
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(day, "18:00"), at(day, "19:00"),
				staff(ANNA, "LEAD") + "," + staff(ANNA, "ASSISTANT"), ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("staff"));
		post("/api/v1/schedule-events", """
				{"kind": "CLASS", "serviceTypeId": "%s", "startsAt": "%s", "endsAt": "%s", "capacity": 11,
				 "resources": [{"resourceId": "%s"}]}
				""".formatted(POLE, at(day, "18:00"), at(day, "19:00"), POLE_ROOM))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("capacity"))
			.andExpect(jsonPath("$.errors[0].code").value("OUT_OF_RANGE"));

		get("/api/v1/schedule-events", "from", at(day, "00:00").toString(), "to", at(day.plusDays(7), "00:00").toString())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void rejectsUnavailableServicesStaffAndResources() throws Exception {
		LocalDate day = this.monday;
		Instant start = at(day, "18:00");
		Instant end = at(day, "19:00");
		post("/api/v1/schedule-events", eventJson("CLASS", ARCHIVED_SERVICE, start, end, "", ""))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_TYPE_UNAVAILABLE"));
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, start, end, staff(FORMER, "LEAD"), ""))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STAFF_UNAVAILABLE"));
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, start, end, staff(UUID.randomUUID(), "LEAD"), ""))
			.andExpect(jsonPath("$.code").value("STAFF_UNAVAILABLE"));
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, start, end, "", room(CLOSED_ROOM, true)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESOURCE_UNAVAILABLE"));
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, start, end, "", room(UUID.randomUUID(), true)))
			.andExpect(jsonPath("$.code").value("RESOURCE_UNAVAILABLE"));
		// Personal training may take place only in the gym.
		post("/api/v1/schedule-events", eventJson("PERSONAL_TRAINING", PERSONAL, start, end, "", room(POLE_ROOM, false)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SERVICE_NOT_ALLOWED_IN_ROOM"));
	}

	@Test
	void aRoomTakenByABlockingEventRejectsTheEventAndListsTheConflict() throws Exception {
		String first = createEvent(poleClass(this.monday, "18:00", "19:00"));

		post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(this.monday, "18:30"), at(this.monday, "19:30"),
				staff(MAREK, "LEAD"), room(POLE_ROOM, false)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"))
			.andExpect(jsonPath("$.conflicts.length()").value(1))
			.andExpect(jsonPath("$.conflicts[0].date").value(this.monday.toString()))
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("RESOURCE_BUSY"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].resourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.conflicts[0].issues[0].eventId").value(first))
			.andExpect(jsonPath("$.conflicts[0].issues[0].eventTitle").value("Pole dance"));

		// Back-to-back events do not overlap.
		createEvent(eventJson("CLASS", POLE, at(this.monday, "19:00"), at(this.monday, "20:00"), staff(MAREK, "LEAD"),
				room(POLE_ROOM, true)));
	}

	@Test
	void blocksARoomSoThatNoEventCanTakeIt() throws Exception {
		LocalDate tuesday = this.monday.plusDays(1);
		var created = post("/api/v1/schedule-events", """
				{"kind": "BLOCK", "serviceTypeId": "%s", "title": "  Serwis klimatyzacji  ", "capacity": 5,
				 "visibility": "PUBLIC", "startsAt": "%s", "endsAt": "%s", "resources": [%s]}
				""".formatted(POLE, at(tuesday, "08:00"), at(tuesday, "12:00"), room(POLE_ROOM, false)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("BLOCK"))
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.visibility").value("PRIVATE"))
			.andExpect(jsonPath("$.title").value("Serwis klimatyzacji"))
			.andExpect(jsonPath("$.blockReason").value(ScheduleEvent.BLOCK_REASON))
			.andExpect(jsonPath("$.serviceTypeId").doesNotExist())
			.andExpect(jsonPath("$.capacity").doesNotExist())
			.andExpect(jsonPath("$.resources[0].resourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(true));
		String block = id(created);

		// Not even an event that shares the room may take it.
		post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(tuesday, "11:00"), at(tuesday, "12:00"),
				staff(ANNA, "LEAD"), room(POLE_ROOM, false)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].eventId").value(block))
			.andExpect(jsonPath("$.conflicts[0].issues[0].eventTitle").value("Serwis klimatyzacji"));

		// Its rooms stay taken whole when they change.
		put("/api/v1/schedule-events/" + block + "/assignments", """
				{"version": 0, "staff": [], "resources": [%s]}""".formatted(room(GYM, false)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resources[0].resourceId").value(GYM.toString()))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(true));
	}

	@Test
	void previewsAndRejectsABlockLikeOtherEvents() throws Exception {
		LocalDate day = this.monday;
		createEvent(poleClass(day, "18:00", "19:00"));

		post("/api/v1/schedule-events/preview", blockJson("Remont", at(day, "17:00"), at(day, "18:30"),
				room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences[0].issues[0].type").value("RESOURCE_BUSY"))
			.andExpect(jsonPath("$.warnings.length()").value(0));
		post("/api/v1/schedule-events", blockJson("Remont", at(day, "17:00"), at(day, "18:30"), room(POLE_ROOM, true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"));

		// The class shares the gym with nobody else, but a block takes even a shared room.
		createEvent(eventJson("CLASS", POLE, at(day, "18:00"), at(day, "19:00"), staff(MAREK, "LEAD"), room(GYM, false)));
		post("/api/v1/schedule-events", blockJson("Sprzątanie", at(day, "18:30"), at(day, "19:30"), room(GYM, false)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"));
	}

	@Test
	void rejectsInvalidBlocks() throws Exception {
		LocalDate day = this.monday;
		post("/api/v1/schedule-events", blockJson("Remont", at(day, "08:00"), at(day, "12:00"), ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("resources"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		post("/api/v1/schedule-events", """
				{"kind": "BLOCK", "title": "Remont", "startsAt": "%s", "endsAt": "%s", "staff": [%s], "resources": [%s]}
				""".formatted(at(day, "08:00"), at(day, "12:00"), staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("staff"));
		post("/api/v1/schedule-events", blockJson(" ", at(day, "08:00"), at(day, "12:00"), room(POLE_ROOM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("title"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		post("/api/v1/schedule-events", blockJson("Remont", at(day, "08:00"), at(day, "12:00"), room(CLOSED_ROOM, true)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("RESOURCE_UNAVAILABLE"));

		String block = createEvent(blockJson("Remont", at(day, "08:00"), at(day, "12:00"), room(POLE_ROOM, true)));
		put("/api/v1/schedule-events/" + block + "/assignments", """
				{"version": 0, "staff": [%s], "resources": [%s]}""".formatted(staff(ANNA, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("staff"));
		put("/api/v1/schedule-events/" + block + "/assignments", """
				{"version": 0, "staff": [], "resources": []}""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("resources"));
	}

	private static String blockJson(String title, Instant startsAt, Instant endsAt, String resources) {
		return """
				{"kind": "BLOCK", "title": "%s", "startsAt": "%s", "endsAt": "%s", "resources": [%s]}
				""".formatted(title, startsAt, endsAt, resources);
	}

	@Test
	void concurrentRequestsCannotBothTakeTheSameRoom() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> request = () -> {
			start.await();
			return post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(this.monday, "18:00"),
					at(this.monday, "19:00"), "", room(POLE_ROOM, true)))
				.andReturn()
				.getResponse()
				.getStatus();
		};
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				results.add(executor.submit(request));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409, 409);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void eventsThatDoNotBlockTheRoomShareIt() throws Exception {
		LocalDate day = this.monday;
		createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(day, "17:00"), at(day, "18:00"), staff(ANNA, "LEAD"),
				room(GYM, false)));
		createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(day, "17:00"), at(day, "18:00"), staff(MAREK, "LEAD"),
				room(GYM, false)));

		post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(day, "17:30"), at(day, "18:30"), "", room(GYM, true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.conflicts[0].issues.length()").value(2));
	}

	@Test
	void cancelledEventsDoNotTakeTheirRoomOrStaff() throws Exception {
		String first = createEvent(poleClass(this.monday, "18:00", "19:00"));
		setStatus(first, "CANCELLED");

		createEvent(poleClass(this.monday, "18:00", "19:00"));
	}

	@Test
	void aStaffMemberCannotRunTwoEventsAtOnce() throws Exception {
		createEvent(poleClass(this.monday, "18:00", "19:00"));

		post("/api/v1/schedule-events", eventJson("CLASS", POLE, at(this.monday, "18:45"), at(this.monday, "19:45"),
				staff(ANNA, "ASSISTANT"), room(GYM, true)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("STAFF_BUSY"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].staffId").value(ANNA.toString()));
	}

	@Test
	void approvedLeaveBlocksAndRequestedLeaveOnlyWarns() throws Exception {
		LocalDate day = this.monday;
		insertTimeOff(ANNA, at(day, "00:00"), at(day.plusDays(1), "00:00"), "APPROVED");
		insertTimeOff(MAREK, at(day, "00:00"), at(day.plusDays(1), "00:00"), "REQUESTED");
		insertTimeOff(MAREK, at(day, "00:00"), at(day.plusDays(1), "00:00"), "REJECTED");

		post("/api/v1/schedule-events", poleClass(day, "18:00", "19:00"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("STAFF_ON_LEAVE"));

		String marekClass = eventJson("CLASS", POLE, at(day, "18:00"), at(day, "19:00"), staff(MAREK, "LEAD"),
				room(POLE_ROOM, true));
		post("/api/v1/schedule-events/preview", marekClass).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(0))
			.andExpect(jsonPath("$.occurrences[0].issues.length()").value(1))
			.andExpect(jsonPath("$.occurrences[0].issues[0].type").value("STAFF_LEAVE_REQUESTED"))
			.andExpect(jsonPath("$.occurrences[0].issues[0].blocking").value(false));
		createEvent(marekClass);
	}

	@Test
	void previewListsConflictsAndWarningsWithoutSaving() throws Exception {
		LocalDate day = this.monday;
		String existing = createEvent(poleClass(day, "18:00", "19:00"));
		// Marek works only on Monday mornings and is not qualified for pole dance.
		this.jdbcTemplate.update("""
				INSERT INTO trainer_availability (staff_id, weekday, local_start_time, local_end_time, valid_from)
				VALUES (?, 1, '08:00', '12:00', CURRENT_DATE)
				""", MAREK);

		post("/api/v1/schedule-events/preview", eventJson("CLASS", POLE, at(day, "18:30"), at(day, "19:30"),
				staff(MAREK, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences.length()").value(1))
			.andExpect(jsonPath("$.occurrences[0].date").value(day.toString()))
			.andExpect(jsonPath("$.occurrences[0].excluded").value(false))
			.andExpect(jsonPath("$.occurrences[0].issues[*].type",
					containsInAnyOrder("RESOURCE_BUSY", "STAFF_OUTSIDE_AVAILABILITY")))
			.andExpect(jsonPath("$.occurrences[0].issues[?(@.type == 'RESOURCE_BUSY')].eventId").value(existing))
			.andExpect(jsonPath("$.warnings.length()").value(1))
			.andExpect(jsonPath("$.warnings[0].type").value("STAFF_NOT_QUALIFIED"))
			.andExpect(jsonPath("$.warnings[0].staffId").value(MAREK.toString()));

		post("/api/v1/schedule-events/preview", eventJson("CLASS", POLE, at(day, "09:00"), at(day, "10:00"),
				staff(MAREK, "LEAD"), room(POLE_ROOM, true)))
			.andExpect(jsonPath("$.conflictCount").value(0))
			.andExpect(jsonPath("$.occurrences[0].issues.length()").value(0));
		get("/api/v1/schedule-events", "from", at(day, "00:00").toString(), "to", at(day.plusDays(1), "00:00").toString())
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void changesTheStaffAndRoomsOfAnEvent() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));

		put("/api/v1/schedule-events/" + id + "/assignments", """
				{"version": 0, "staff": [{"staffId": "%s", "role": "LEAD"}],
				 "resources": [{"resourceId": "%s", "blocksWholeRoom": true}]}
				""".formatted(MAREK, GYM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.staff.length()").value(1))
			.andExpect(jsonPath("$.staff[0].staffId").value(MAREK.toString()))
			.andExpect(jsonPath("$.resources.length()").value(1))
			.andExpect(jsonPath("$.resources[0].name").value("Gym"))
			.andExpect(jsonPath("$.isSeriesException").value(false))
			.andExpect(jsonPath("$.version").value(1));

		// The pole room is free again.
		createEvent(eventJson("CLASS", POLE, at(this.monday, "18:00"), at(this.monday, "19:00"), staff(ANNA, "LEAD"),
				room(POLE_ROOM, true)));

		put("/api/v1/schedule-events/" + id + "/assignments", """
				{"version": 1, "staff": [], "resources": []}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.staff.length()").value(0))
			.andExpect(jsonPath("$.resources.length()").value(0))
			.andExpect(jsonPath("$.version").value(2));
	}

	@Test
	void changingTheRoleOfAStaffMemberKeepsThemAssigned() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));

		put("/api/v1/schedule-events/" + id + "/assignments", """
				{"version": 0, "staff": [{"staffId": "%s", "role": "ASSISTANT"}, {"staffId": "%s", "role": "LEAD"}],
				 "resources": [{"resourceId": "%s", "blocksWholeRoom": true}]}
				""".formatted(ANNA, MAREK, POLE_ROOM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.staff[0].displayName").value("Marek Nowak"))
			.andExpect(jsonPath("$.staff[0].role").value("LEAD"))
			.andExpect(jsonPath("$.staff[1].displayName").value("Anna Kowalska"))
			.andExpect(jsonPath("$.staff[1].role").value("ASSISTANT"));
	}

	@Test
	void changingAssignmentsChecksConflictsButNotWithTheEventItself() throws Exception {
		String annasClass = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String mareksClass = createEvent(eventJson("CLASS", POLE, at(this.monday, "18:00"), at(this.monday, "19:00"),
				staff(MAREK, "LEAD"), room(GYM, true)));

		put("/api/v1/schedule-events/" + mareksClass + "/assignments", """
				{"version": 0, "staff": [{"staffId": "%s"}], "resources": [{"resourceId": "%s"}]}
				""".formatted(ANNA, GYM))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].eventId").value(annasClass));

		put("/api/v1/schedule-events/" + annasClass + "/assignments", """
				{"version": 0, "staff": [{"staffId": "%s"}], "resources": [{"resourceId": "%s", "blocksWholeRoom": false}]}
				""".formatted(ANNA, POLE_ROOM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(false));
	}

	@Test
	void keepsStaffAndRoomsThatWereDeactivatedAfterTheyWereAssigned() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		this.jdbcTemplate.update("UPDATE staff_profile SET status = 'INACTIVE' WHERE id = ?", ANNA);
		this.jdbcTemplate.update("UPDATE resource SET is_active = false WHERE id = ?", POLE_ROOM);
		this.jdbcTemplate.update("DELETE FROM service_type_resource WHERE resource_id = ?", POLE_ROOM);

		put("/api/v1/schedule-events/" + id + "/assignments", """
				{"version": 0, "staff": [{"staffId": "%s"}, {"staffId": "%s", "role": "ASSISTANT"}],
				 "resources": [{"resourceId": "%s"}, {"resourceId": "%s"}]}
				""".formatted(ANNA, MAREK, POLE_ROOM, GYM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.staff.length()").value(2))
			.andExpect(jsonPath("$.resources.length()").value(2));

		// A new event cannot get them.
		post("/api/v1/schedule-events", poleClass(this.monday.plusDays(1), "18:00", "19:00"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STAFF_UNAVAILABLE"));
	}

	@Test
	void rejectsAStaleVersionAndEventsThatAreNotScheduled() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String change = """
				{"version": %d, "staff": [{"staffId": "%s"}], "resources": []}
				""";

		put("/api/v1/schedule-events/" + id + "/assignments", change.formatted(3, MAREK))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		put("/api/v1/schedule-events/" + UUID.randomUUID() + "/assignments", change.formatted(0, MAREK))
			.andExpect(status().isNotFound());
		put("/api/v1/schedule-events/" + id + "/assignments", """
				{"version": 0, "staff": [{"staffId": "%s"}]}
				""".formatted(MAREK))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("resources"));

		setStatus(id, "CANCELLED");
		put("/api/v1/schedule-events/" + id + "/assignments", change.formatted(0, MAREK))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
	}

	@Test
	void timetableReturnsEventsOverlappingTheWindowAndAppliesFilters() throws Exception {
		LocalDate day = this.monday;
		String pole = createEvent(poleClass(day, "18:00", "19:00"));
		String personal = createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(day, "07:00"), at(day, "08:00"),
				staff(MAREK, "LEAD"), room(GYM, false)));
		String late = createEvent(eventJson("WORKSHOP", POLE, at(day, "23:30"), at(day.plusDays(1), "00:30"),
				staff(ANNA, "LEAD"), room(GYM, true)));
		String cancelled = createEvent(eventJson("CLASS", POLE, at(day, "10:00"), at(day, "11:00"), "", ""));
		setStatus(cancelled, "CANCELLED");
		String from = at(day, "00:00").toString();
		String to = at(day.plusDays(1), "00:00").toString();

		get("/api/v1/schedule-events", "from", from, "to", to).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(personal, cancelled, pole, late)));
		// The late workshop overlaps the next day too.
		get("/api/v1/schedule-events", "from", to, "to", at(day.plusDays(2), "00:00").toString())
			.andExpect(jsonPath("$.items[*].id").value(org.hamcrest.Matchers.contains(late)));

		get("/api/v1/schedule-events", "from", from, "to", to, "kind", "CLASS", "kind", "WORKSHOP")
			.andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(cancelled, pole, late)));
		get("/api/v1/schedule-events", "from", from, "to", to, "status", "SCHEDULED", "staffId", ANNA.toString())
			.andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(pole, late)));
		get("/api/v1/schedule-events", "from", from, "to", to, "resourceId", GYM.toString())
			.andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(personal, late)));
		get("/api/v1/schedule-events", "from", from, "to", to, "serviceTypeId", PERSONAL.toString(), "visibility",
				"PRIVATE")
			.andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(personal)));
		get("/api/v1/schedule-events", "from", from, "to", to, "status", "CANCELLED")
			.andExpect(jsonPath("$.items[*].id").value(containsInAnyOrder(cancelled)));
	}

	@Test
	void timetableNeedsAValidWindow() throws Exception {
		Instant from = at(this.monday, "00:00");
		get("/api/v1/schedule-events", "from", from.toString(), "to", from.toString())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("to"));
		get("/api/v1/schedule-events", "from", from.toString(), "to", from.plusSeconds(94L * 24 * 3600).toString())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("to"));
		get("/api/v1/schedule-events", "to", from.toString()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		get("/api/v1/schedule-events", "from", from.toString(), "to", from.plusSeconds(3600).toString(), "kind",
				"PARTY")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		get("/api/v1/schedule-events/" + UUID.randomUUID()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void scheduleIsForAdministratorsOnly() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String window = "?from=" + at(this.monday, "00:00") + "&to=" + at(this.monday.plusDays(1), "00:00");

		this.anonymous.perform(MockMvcRequestBuilders.get("/api/v1/schedule-events" + window))
			.andExpect(status().isUnauthorized());
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.get("/api/v1/schedule-events" + window)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.post("/api/v1/schedule-events")
			.contentType(MediaType.APPLICATION_JSON)
			.content(poleClass(this.monday, "20:00", "21:00")))).andExpect(status().isForbidden());
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.put("/api/v1/schedule-events/" + id + "/assignments")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"version": 0, "staff": [], "resources": []}
					"""))).andExpect(status().isForbidden());
		this.mockMvc.perform(asTrainer(MockMvcRequestBuilders.get("/api/v1/staff"))).andExpect(status().isForbidden());

		get("/api/v1/schedule-events/" + id).andExpect(jsonPath("$.staff.length()").value(1));
	}

	@Test
	void listsStaffWithTheirQualificationsAndTrainingGroups() throws Exception {
		get("/api/v1/staff", "isActive", "true").andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].displayName", hasItem("Anna Kowalska")))
			.andExpect(jsonPath("$.items[?(@.id == '" + FORMER + "')]").isEmpty())
			.andExpect(jsonPath("$.items[?(@.id == '" + ANNA + "')].serviceTypeIds[0]").value(POLE.toString()))
			.andExpect(jsonPath("$.items[?(@.id == '" + ANNA + "')].calendarColor").value("#E91E63"));
		get("/api/v1/staff", "isActive", "false")
			.andExpect(jsonPath("$.items[?(@.id == '" + FORMER + "')].isActive").value(false));

		this.jdbcTemplate.update("INSERT INTO training_group (name, service_type_id) VALUES ('Beginners Tue', ?)",
				POLE);
		get("/api/v1/training-groups").andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Beginners Tue"))
			.andExpect(jsonPath("$.items[0].serviceTypeId").value(POLE.toString()))
			.andExpect(jsonPath("$.items[0].isActive").value(true));
		get("/api/v1/training-groups", "isActive", "false").andExpect(jsonPath("$.items.length()").value(0));
	}

}
