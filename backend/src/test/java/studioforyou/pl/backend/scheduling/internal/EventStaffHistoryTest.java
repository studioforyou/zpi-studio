package studioforyou.pl.backend.scheduling.internal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class EventStaffHistoryTest extends ScheduleTestSupport {

	@Test
	void recordsManualChangesWithTheirAuthorAndKeepsNamesAfterARename() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		assign(event, 0, staff(MAREK, "LEAD"), POLE_ROOM).andExpect(status().isOk());
		this.jdbcTemplate.update("UPDATE staff_profile SET last_name='Changed' WHERE id=?", ANNA);
		setStatus(event, "COMPLETED");
		history(event).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("MANUAL"))
			.andExpect(jsonPath("$.items[0].actorAccountId").isNotEmpty())
			.andExpect(jsonPath("$.items[0].occurredAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].before[0].displayName").value("Anna Kowalska"))
			.andExpect(jsonPath("$.items[0].after[0].displayName").value("Marek Nowak"));
	}

	@Test
	void roomChangesAndReorderedAssignmentsDoNotCreateHistory() throws Exception {
		String event = createEvent(eventJson("CLASS", POLE, at(this.monday, "18:00"), at(this.monday, "19:00"),
				staff(ANNA, "LEAD") + "," + staff(MAREK, "ASSISTANT"), room(POLE_ROOM, true)));
		assign(event, 0, staff(MAREK, "ASSISTANT") + "," + staff(ANNA, "LEAD"), GYM)
			.andExpect(status().isOk());
		assign(event, 1, staff(ANNA, "LEAD") + "," + staff(MAREK, "ASSISTANT"), GYM)
			.andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void aRoleChangeAndRemovingStaffAreRealChangesNewestFirst() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		assign(event, 0, staff(ANNA, "ASSISTANT"), POLE_ROOM).andExpect(status().isOk());
		assign(event, 1, "", POLE_ROOM).andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].before[0].role").value("ASSISTANT"))
			.andExpect(jsonPath("$.items[0].after").isEmpty())
			.andExpect(jsonPath("$.items[1].before[0].role").value("LEAD"))
			.andExpect(jsonPath("$.items[1].after[0].role").value("ASSISTANT"));
	}

	@Test
	void fullEventEditingAlsoRecordsTheStaffChange() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		put("/api/v1/schedule-events/" + event, """
				{"version":0,"startsAt":"%s","endsAt":"%s","staff":[%s],"resources":[%s]}
				""".formatted(at(this.monday,"18:00"), at(this.monday,"19:00"), staff(MAREK,"LEAD"), room(POLE_ROOM,true)))
			.andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].after[0].staffId").value(MAREK.toString()));
	}

	@Test
	void everyChangedOccurrenceHasHistoryAndRestoringTheRuleAddsAnotherEntry() throws Exception {
		post("/api/v1/recurrence-series", """
				{"kind":"CLASS","serviceTypeId":"%s","weekdays":[1],"localStartTime":"18:00",
				 "durationMinutes":60,"startsOn":"%s","endsOn":"%s","staff":[%s],"resources":[%s]}
				""".formatted(POLE,this.monday,this.monday.plusWeeks(1),staff(ANNA,"LEAD"),room(POLE_ROOM,true)))
			.andExpect(status().isCreated());
		List<String> ids = JsonPath.read(get("/api/v1/schedule-events", "from", at(this.monday,"00:00").toString(),
				"to", at(this.monday.plusWeeks(2),"00:00").toString()).andReturn().getResponse().getContentAsString(), "$.items[*].id");
		put("/api/v1/schedule-events/" + ids.getFirst() + "/assignments", """
				{"version":0,"scope":"THIS_AND_FOLLOWING","staff":[%s],"resources":[%s]}
				""".formatted(staff(MAREK,"LEAD"),room(POLE_ROOM,true))).andExpect(status().isOk());
		for (String id : ids) history(id).andExpect(jsonPath("$.items.length()").value(1));
		assign(ids.getFirst(), 1, staff(ANNA,"LEAD"), POLE_ROOM).andExpect(status().isOk());
		post("/api/v1/schedule-events/" + ids.getFirst() + "/restore-series", "{\"version\":2}").andExpect(status().isOk());
		history(ids.getFirst()).andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].kind").value("SERIES_RESTORED"))
			.andExpect(jsonPath("$.items[0].before[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.items[0].after[0].staffId").value(MAREK.toString()));
	}

	@Test
	void readsOlderApprovedSubstitutionsButNotRejectedRequestsOrTheirPrivateReasons() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		UUID actor = this.jdbcTemplate.queryForObject("SELECT id FROM account WHERE email='admin@schedule.test'", UUID.class);
		this.jdbcTemplate.update("""
				INSERT INTO substitution_request(event_id,original_staff_id,requested_by,status,substitute_staff_id,
				  decided_by,decided_at,reason) VALUES (?,?,?,'APPROVED',?,?,now(),'Private absence reason')
				""", UUID.fromString(event), ANNA, actor, MAREK, actor);
		this.jdbcTemplate.update("""
				INSERT INTO substitution_request(event_id,original_staff_id,requested_by,status,substitute_staff_id,
				  decided_by,decided_at) VALUES (?,?,?,'REJECTED',?,?,now())
				""", UUID.fromString(event), ANNA, actor, MAREK, actor);
		history(event).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("SUBSTITUTION"))
			.andExpect(jsonPath("$.items[0].before[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.items[0].after[0].staffId").value(MAREK.toString()))
			.andExpect(jsonPath("$.items[0].reason").doesNotExist());
	}

	@Test
	void conflictsAndStaleVersionsDoNotLeaveHistoryAndOnlyAdminsCanReadIt() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday,"18:00"), at(this.monday,"19:00"),
				staff(MAREK,"LEAD"),room(GYM,true)));
		assign(event, 0, staff(MAREK,"LEAD"), POLE_ROOM).andExpect(status().isConflict());
		assign(event, 3, staff(ANNA,"LEAD"), POLE_ROOM).andExpect(status().isConflict());
		history(event).andExpect(jsonPath("$.items").isEmpty());
		this.mockMvc.perform(as(ANNA_TOKEN, MockMvcRequestBuilders.get(path(event)))).andExpect(status().isForbidden());
		this.anonymous.perform(MockMvcRequestBuilders.get(path(event))).andExpect(status().isUnauthorized());
		history(UUID.randomUUID().toString()).andExpect(status().isNotFound());
	}

	private org.springframework.test.web.servlet.ResultActions assign(String event, int version, String members, UUID resource) throws Exception {
		return put("/api/v1/schedule-events/" + event + "/assignments", """
				{"version":%d,"staff":[%s],"resources":[%s]}
				""".formatted(version,members,room(resource,true)));
	}

	private org.springframework.test.web.servlet.ResultActions history(String event) throws Exception { return get(path(event)); }
	private static String path(String event) { return "/api/v1/schedule-events/" + event + "/staff-history"; }
}
