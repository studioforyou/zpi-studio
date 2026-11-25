package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class AdminConsultationTest extends ScheduleTestSupport {

	@Test
	void reservesWholeRoomPrivatelyWithoutCreatingCustomerOrChargingPass() throws Exception {
		List<Integer> before = customerCounts();
		String event = id(post("/api/v1/schedule-events", consultation(GYM, staff(ANNA, "LEAD"), "  Alicja Zielińska  "))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("CONSULTATION"))
			.andExpect(jsonPath("$.visibility").value("PRIVATE"))
			.andExpect(jsonPath("$.capacity").isEmpty())
			.andExpect(jsonPath("$.serviceTypeId").isEmpty())
			.andExpect(jsonPath("$.guestName").value("Alicja Zielińska"))
			.andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(true)));
		get("/api/v1/me/schedule-events/" + event).andExpect(status().isForbidden());
		this.mockMvc.perform(as(ANNA_TOKEN, MockMvcRequestBuilders.get("/api/v1/me/schedule-events/" + event)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.guestName").value("Alicja Zielińska"));
		assertThat(customerCounts()).isEqualTo(before);
	}

	@Test
	void guestAndTrainerAreOptionalAndPreviewDoesNotWrite() throws Exception {
		String request = consultation(GYM, "", "   ");
		post("/api/v1/schedule-events/preview", request).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(0));
		assertThat(count("schedule_event")).isZero();
		post("/api/v1/schedule-events", request).andExpect(status().isCreated())
			.andExpect(jsonPath("$.guestName").isEmpty()).andExpect(jsonPath("$.staff").isEmpty());
	}

	@Test
	void roomAndTrainerConflictsBlockSavingIncludingApprovedLeave() throws Exception {
		createEvent(poleClass(this.monday, "18:00", "19:00"));
		String roomTaken = consultation(POLE_ROOM, staff(MAREK, "LEAD"), "Jan");
		post("/api/v1/schedule-events/preview", roomTaken).andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1));
		post("/api/v1/schedule-events", roomTaken).andExpect(status().isConflict());
		post("/api/v1/schedule-events", consultation(GYM, staff(ANNA, "LEAD"), "Jan"))
			.andExpect(status().isConflict());
		insertTimeOff(MAREK, at(this.monday, "17:00"), at(this.monday, "20:00"), "APPROVED");
		post("/api/v1/schedule-events", consultation(GYM, staff(MAREK, "LEAD"), "Jan"))
			.andExpect(status().isConflict());
		assertThat(count("schedule_event")).isEqualTo(1);
	}

	@Test
	void requiresAnActiveRoomAtMostOneLeadAndValidFutureTimes() throws Exception {
		post("/api/v1/schedule-events", consultation(GYM, "", "Jan").replace(room(GYM, false), ""))
			.andExpect(status().isBadRequest());
		post("/api/v1/schedule-events", consultation(CLOSED_ROOM, "", "Jan"))
			.andExpect(status().isUnprocessableEntity());
		post("/api/v1/schedule-events", consultation(GYM, staff(FORMER, "LEAD"), "Jan"))
			.andExpect(status().isUnprocessableEntity());
		post("/api/v1/schedule-events", consultation(GYM, staff(ANNA, "ASSISTANT"), "Jan"))
			.andExpect(status().isBadRequest());
		post("/api/v1/schedule-events", consultation(GYM, staff(ANNA, "LEAD") + "," + staff(MAREK, "LEAD"), "Jan"))
			.andExpect(status().isBadRequest());
		post("/api/v1/schedule-events", consultation(GYM, "", "a".repeat(121))).andExpect(status().isBadRequest());
		post("/api/v1/schedule-events", consultation(GYM, "", "Jan")
			.replace(at(this.monday, "19:00").toString(), at(this.monday, "17:00").toString()))
			.andExpect(status().isBadRequest());
		post("/api/v1/schedule-events", consultation(GYM, "", "Jan")
			.replace(at(this.monday, "18:00").toString(), "2020-01-01T00:00:00Z"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void canMoveRenameAndClearGuestWithoutChangingOtherEvents() throws Exception {
		String event = createEvent(consultation(GYM, staff(ANNA, "LEAD"), "Jan"));
		String change = """
				{"version":0,"startsAt":"%s","endsAt":"%s","title":"Rozmowa wstępna","guestName":null,
				 "capacity":99,"staff":[],"resources":[%s]}
				""".formatted(at(this.monday.plusDays(1), "17:00"), at(this.monday.plusDays(1), "17:30"), room(POLE_ROOM, false));
		post("/api/v1/schedule-events/" + event + "/change-preview", change).andExpect(status().isOk());
		put("/api/v1/schedule-events/" + event, change).andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Rozmowa wstępna"))
			.andExpect(jsonPath("$.guestName").isEmpty()).andExpect(jsonPath("$.capacity").isEmpty())
			.andExpect(jsonPath("$.staff").isEmpty()).andExpect(jsonPath("$.resources[0].blocksWholeRoom").value(true));
		put("/api/v1/schedule-events/" + event, change).andExpect(status().isConflict());
		assertThat(count("schedule_event")).isEqualTo(1);
	}

	@Test
	void assignmentEndpointCannotBypassConsultationRules() throws Exception {
		String event = createEvent(consultation(GYM, "", "Jan"));
		put("/api/v1/schedule-events/" + event + "/assignments", """
				{"version":0,"staff":[],"resources":[]}
				""").andExpect(status().isBadRequest());
		this.jdbcTemplate.update("UPDATE schedule_event SET starts_at=now()-interval '2 hours', ends_at=now()-interval '1 hour' WHERE id=?", UUID.fromString(event));
		put("/api/v1/schedule-events/" + event + "/assignments", """
				{"version":0,"staff":[],"resources":[%s]}
				""".formatted(room(GYM, true))).andExpect(status().isUnprocessableEntity());
	}

	@Test
	void cancellationReleasesRoomAndDoesNotTouchPasses() throws Exception {
		List<Integer> before = customerCounts();
		String event = createEvent(consultation(GYM, "", "Jan"));
		post("/api/v1/schedule-events/" + event + "/cancel", "{\"version\":0,\"reason\":\"Gość zmienił plany\"}")
			.andExpect(status().isOk());
		get("/api/v1/schedule-events/" + event).andExpect(status().isOk())
			.andExpect(jsonPath("$.cancelReason").value("Gość zmienił plany"));
		createEvent(consultation(GYM, "", "Alicja"));
		assertThat(customerCounts()).isEqualTo(before);
	}

	@Test
	void consultationsAreOneOffAndAdminOnly() throws Exception {
		String series = """
				{"kind":"CONSULTATION","weekdays":[1],"localStartTime":"18:00","durationMinutes":30,
				 "startsOn":"%s","endsOn":"%s","resources":[%s]}
				""".formatted(this.monday, this.monday.plusWeeks(1), room(GYM, true));
		for (String suffix : List.of("", "/preview")) post("/api/v1/recurrence-series" + suffix, series)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("kind"));
		for (String suffix : List.of("", "/preview")) {
			var request = MockMvcRequestBuilders.post("/api/v1/schedule-events" + suffix)
				.contentType(MediaType.APPLICATION_JSON).content(consultation(GYM, "", "Jan"));
			this.anonymous.perform(request).andExpect(status().isUnauthorized());
			this.mockMvc.perform(as(ANNA_TOKEN, request)).andExpect(status().isForbidden());
		}
	}

	private String consultation(UUID room, String members, String guest) {
		return """
				{"kind":"CONSULTATION","serviceTypeId":"%s","capacity":10,"visibility":"PUBLIC",
				 "startsAt":"%s","endsAt":"%s","staff":[%s],"resources":[%s],"guestName":"%s"}
				""".formatted(POLE, at(this.monday, "18:00"), at(this.monday, "19:00"), members, room(room, false), guest);
	}

	private List<Integer> customerCounts() {
		return List.of(count("account"), count("customer_profile"), count("reservation"), count("pass_ledger"));
	}

	private int count(String table) {
		return this.jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}
}
