package studioforyou.pl.backend.scheduling.internal;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import studioforyou.pl.backend.scheduling.ScheduledEvents;

class EventHistoryTest extends ScheduleTestSupport {

	@Autowired ScheduledEvents scheduledEvents;
	@Autowired PlatformTransactionManager transactions;

	@Test
	void recordsManualChangesWithTheirAuthorAndKeepsNamesAfterARename() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		assign(event, 0, staff(MAREK, "LEAD"), POLE_ROOM).andExpect(status().isOk());
		this.jdbcTemplate.update("UPDATE staff_profile SET last_name='Changed' WHERE id=?", ANNA);
		setStatus(event, "COMPLETED");
		history(event).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].kind").value("MANUAL"))
			.andExpect(jsonPath("$.items[0].actorAccountId").isNotEmpty())
			.andExpect(jsonPath("$.items[0].occurredAt").isNotEmpty())
			.andExpect(jsonPath("$.items[0].changes.staff.old[0].displayName").value("Anna Kowalska"))
			.andExpect(jsonPath("$.items[0].changes.staff.new[0].displayName").value("Marek Nowak"));
	}

	@Test
	void roomChangesAreRecordedButReorderedAssignmentsDoNotDuplicateHistory() throws Exception {
		String event = createEvent(eventJson("CLASS", POLE, at(this.monday, "18:00"), at(this.monday, "19:00"),
				staff(ANNA, "LEAD") + "," + staff(MAREK, "ASSISTANT"), room(POLE_ROOM, true)));
		assign(event, 0, staff(MAREK, "ASSISTANT") + "," + staff(ANNA, "LEAD"), GYM)
			.andExpect(status().isOk());
		assign(event, 1, staff(ANNA, "LEAD") + "," + staff(MAREK, "ASSISTANT"), GYM)
			.andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].changes.resources.old[0].resourceId").value(POLE_ROOM.toString()))
			.andExpect(jsonPath("$.items[0].changes.resources.new[0].resourceId").value(GYM.toString()))
			.andExpect(jsonPath("$.items[0].changes.staff").doesNotExist());
	}

	@Test
	void aRoleChangeAndRemovingStaffAreRealChangesNewestFirst() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		assign(event, 0, staff(ANNA, "ASSISTANT"), POLE_ROOM).andExpect(status().isOk());
		assign(event, 1, "", POLE_ROOM).andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].changes.staff.old[0].role").value("ASSISTANT"))
			.andExpect(jsonPath("$.items[0].changes.staff.new").isEmpty())
			.andExpect(jsonPath("$.items[1].changes.staff.old[0].role").value("LEAD"))
			.andExpect(jsonPath("$.items[1].changes.staff.new[0].role").value("ASSISTANT"));
	}

	@Test
	void fullEventEditingAlsoRecordsTheStaffChange() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		put("/api/v1/schedule-events/" + event, """
				{"version":0,"startsAt":"%s","endsAt":"%s","staff":[%s],"resources":[%s]}
				""".formatted(at(this.monday,"18:00"), at(this.monday,"19:00"), staff(MAREK,"LEAD"), room(POLE_ROOM,true)))
			.andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].changes.staff.new[0].staffId").value(MAREK.toString()));
	}

	@Test
	void oneEditRecordsTimeRoomNameAndCapacityTogetherWithoutUnchangedStaff() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		put("/api/v1/schedule-events/" + event, """
				{"version":0,"startsAt":"%s","endsAt":"%s","title":"Nowa nazwa","capacity":8,
				 "staff":[%s],"resources":[%s]}
				""".formatted(at(this.monday.plusDays(1),"19:00"),at(this.monday.plusDays(1),"20:30"),
				staff(ANNA,"LEAD"),room(GYM,true))).andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].changes.time.old.startsAt").value(at(this.monday,"18:00").toString()))
			.andExpect(jsonPath("$.items[0].changes.time.new.endsAt").value(at(this.monday.plusDays(1),"20:30").toString()))
			.andExpect(jsonPath("$.items[0].changes.resources.old[0].name").value("Pole room"))
			.andExpect(jsonPath("$.items[0].changes.resources.new[0].name").value("Gym"))
			.andExpect(jsonPath("$.items[0].changes.title.new").value("Nowa nazwa"))
			.andExpect(jsonPath("$.items[0].changes.capacity.old").value(10))
			.andExpect(jsonPath("$.items[0].changes.capacity.new").value(8))
			.andExpect(jsonPath("$.items[0].changes.staff").doesNotExist());
	}

	@Test
	void trainerReschedulesAreRecordedWithTheTrainerAsAuthor() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		this.mockMvc.perform(as(ANNA_TOKEN,MockMvcRequestBuilders.put("/api/v1/me/schedule-events/" + event + "/reschedule")
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""
				{"version":0,"startsAt":"%s","endsAt":"%s","resourceId":"%s"}
				""".formatted(at(this.monday,"19:00"),at(this.monday,"20:00"),GYM))))
			.andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].actorName").value("Anna Kowalska"))
			.andExpect(jsonPath("$.items[0].changes.time").exists())
			.andExpect(jsonPath("$.items[0].changes.resources").exists());
	}

	@Test
	void includesCreationAndCancellationWithItsAuthorAndReason() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		history(event).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("CREATED"));
		post("/api/v1/schedule-events/" + event + "/cancel","{\"version\":0,\"reason\":\"Prace w sali\"}")
			.andExpect(status().isOk());
		history(event).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].kind").value("CANCELLED"))
			.andExpect(jsonPath("$.items[0].changes.status.old").value("SCHEDULED"))
			.andExpect(jsonPath("$.items[0].changes.status.new").value("CANCELLED"))
			.andExpect(jsonPath("$.items[0].reason").value("Prace w sali"));
	}

	@Test
	void readsSystemDecisionsAndLegacyStaffEntriesInTheSameHistory() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		this.jdbcTemplate.update("""
				INSERT INTO audit_log(module,entity_type,entity_id,action,changes,reason)
				VALUES ('scheduling','schedule_event',?,'EVENT_CANCELLED',
				'{"status":{"old":"SCHEDULED","new":"CANCELLED"}}'::jsonb,'Za mało uczestników')
				""", UUID.fromString(event));
		history(event).andExpect(jsonPath("$.items[0].kind").value("CANCELLED"))
			.andExpect(jsonPath("$.items[0].actorAccountId").doesNotExist())
			.andExpect(jsonPath("$.items[0].reason").value("Za mało uczestników"));
		// Retain the first version of this unmerged PR's audit records as well.
		this.jdbcTemplate.update("""
				INSERT INTO audit_log(module,entity_type,entity_id,action,changes)
				VALUES ('scheduling','schedule_event',?,'STAFF_MANUAL',
				'{"staff":{"old":[],"new":[]}}'::jsonb)
				""", UUID.fromString(event));
		history(event).andExpect(jsonPath("$.items.length()").value(3));
	}

	@Test
	void forceRunAndCompletionUseTheExistingBusinessAuditWithoutDuplicates() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		UUID id = UUID.fromString(event);
		UUID actor = this.jdbcTemplate.queryForObject("SELECT id FROM account WHERE email='admin@schedule.test'",UUID.class);
		new TransactionTemplate(this.transactions).executeWithoutResult(transaction -> {
			this.scheduledEvents.lockAttended(id);
			this.scheduledEvents.changeForceRun(id,true,actor);
			this.scheduledEvents.changeForceRun(id,true,actor);
			this.scheduledEvents.markCompleted(id,actor);
			this.scheduledEvents.markCompleted(id,actor);
		});
		history(event).andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].kind").value("COMPLETED"))
			.andExpect(jsonPath("$.items[0].changes.status.new").value("COMPLETED"))
			.andExpect(jsonPath("$.items[1].kind").value("FORCE_RUN_CHANGED"))
			.andExpect(jsonPath("$.items[1].changes.forceRun.old").value(false))
			.andExpect(jsonPath("$.items[1].changes.forceRun.new").value(true));
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
		for (String id : ids) history(id).andExpect(jsonPath("$.items.length()").value(2));
		assign(ids.getFirst(), 1, staff(ANNA,"LEAD"), POLE_ROOM).andExpect(status().isOk());
		post("/api/v1/schedule-events/" + ids.getFirst() + "/restore-series", "{\"version\":2}").andExpect(status().isOk());
		history(ids.getFirst()).andExpect(jsonPath("$.items.length()").value(4))
			.andExpect(jsonPath("$.items[0].kind").value("SERIES_RESTORED"))
			.andExpect(jsonPath("$.items[0].changes.staff.old[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.items[0].changes.staff.new[0].staffId").value(MAREK.toString()));
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
		history(event).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].kind").value("SUBSTITUTION"))
			.andExpect(jsonPath("$.items[0].changes.staff.old[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.items[0].changes.staff.new[0].staffId").value(MAREK.toString()))
			.andExpect(jsonPath("$.items[0].reason").doesNotExist());
	}

	@Test
	void conflictsAndStaleVersionsDoNotLeaveHistoryAndOnlyAdminsCanReadIt() throws Exception {
		String event = createEvent(poleClass(this.monday,"18:00","19:00"));
		createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday,"18:00"), at(this.monday,"19:00"),
				staff(MAREK,"LEAD"),room(GYM,true)));
		assign(event, 0, staff(MAREK,"LEAD"), POLE_ROOM).andExpect(status().isConflict());
		assign(event, 3, staff(ANNA,"LEAD"), POLE_ROOM).andExpect(status().isConflict());
		history(event).andExpect(jsonPath("$.items.length()").value(1));
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
	private static String path(String event) { return "/api/v1/schedule-events/" + event + "/history"; }
}
