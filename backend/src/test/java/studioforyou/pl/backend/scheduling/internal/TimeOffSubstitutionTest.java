package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import studioforyou.pl.backend.communication.notification.Notifications;

class TimeOffSubstitutionTest extends ScheduleTestSupport {

	@MockitoBean Notifications notifications;
	@org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationEventPublisher publisher;
	@org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.support.TransactionTemplate transactions;

	@Test
	void savesSelectionAndOpensOnlySelectedOffersAfterApproval() throws Exception {
		String first = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String second = createEvent(poleClass(this.monday, "12:00", "13:00"));
		createEvent(poleClass(this.monday, "14:00", "15:00"));
		String id = request(List.of(first, second));
		assertThat(countRequests()).isZero();
		get(id).andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[2].selected").value(false));
		approve(id, 0).andExpect(status().isOk());
		assertThat(countRequests()).isEqualTo(2);
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM substitution_request WHERE reason IS NOT NULL", Integer.class)).isZero();
		assertThat(lead(first)).isEqualTo(ANNA);
		approve(id, 1).andExpect(status().isConflict());
		assertThat(countRequests()).isEqualTo(2);
	}

	@Test
	void foreignOrOutOfPeriodSelectionRollsBackTheWholeRequest() throws Exception {
		String foreign = createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday, "10:00"),
				at(this.monday, "11:00"), staff(MAREK, "LEAD"), room(GYM, true)));
		post(ANNA_TOKEN, "/api/v1/me/time-off", absenceBody(List.of(foreign))).andExpect(status().isConflict());
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM staff_time_off", Integer.class)).isZero();
	}

	@Test
	void editsPreserveUncheckedChoicesAndStaleApprovalIsRejected() throws Exception {
		String first = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String second = createEvent(poleClass(this.monday, "12:00", "13:00"));
		String id = request(List.of(first));
		this.mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/me/time-off/" + id)
			.header("Authorization", "Bearer " + ANNA_TOKEN).contentType(MediaType.APPLICATION_JSON)
			.content(absenceBody(List.of(second)).replace("\"kind\"", "\"version\":0,\"kind\"")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
		approve(id, 0).andExpect(status().isConflict());
		approve(id, 1).andExpect(status().isOk());
		assertThat(this.jdbcTemplate.queryForObject("SELECT event_id FROM substitution_request", UUID.class))
			.isEqualTo(UUID.fromString(second));
		this.mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/me/time-off/" + id + "/substitutions")
			.header("Authorization", "Bearer " + MAREK_TOKEN)).andExpect(status().isNotFound());
	}

	@Test
	void cancellingClosesReopenedAbsenceOffersAndKeepsIndependentRequests() throws Exception {
		qualify();
		String first = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String second = createEvent(poleClass(this.monday, "12:00", "13:00"));
		post(ANNA_TOKEN, "/api/v1/me/schedule-events/" + second + "/substitution-request", "{}")
			.andExpect(status().isCreated());
		String id = request(List.of(first, second));
		approve(id, 0).andExpect(status().isOk());
		String offer = this.jdbcTemplate.queryForObject("SELECT id::text FROM substitution_request WHERE event_id=?", String.class, UUID.fromString(first));
		post(MAREK_TOKEN, "/api/v1/me/substitution-offers/" + offer + "/claim", "{\"version\":0}").andExpect(status().isOk());
		post(MAREK_TOKEN, "/api/v1/me/substitution-offers/" + offer + "/withdraw", "{\"version\":1}").andExpect(status().isOk());
		post(ANNA_TOKEN, "/api/v1/me/time-off/" + id + "/cancel", "{}").andExpect(status().isOk());
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM substitution_request WHERE time_off_id=? AND status IN ('OPEN','CLAIMED')",
			Integer.class, UUID.fromString(id))).isZero();
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM substitution_request WHERE event_id=?", String.class, UUID.fromString(second))).isEqualTo("OPEN");
	}

	@Test
	void canAssignSeveralSessionsTogetherAndCancellingLeaveDoesNotUndoAgreedCover() throws Exception {
		qualify();
		String first = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String second = createEvent(poleClass(this.monday, "12:00", "13:00"));
		String id = request(List.of());
		approve(id, 0).andExpect(status().isOk());
		String input = batch(id, 1, true);
		post(ADMIN_TOKEN, path(id) + "/assign", input).andExpect(status().isOk());
		assertThat(lead(first)).isEqualTo(MAREK);
		assertThat(lead(second)).isEqualTo(MAREK);
		post(ADMIN_TOKEN, path(id) + "/assign", input).andExpect(status().isConflict());
		post(ANNA_TOKEN, "/api/v1/me/time-off/" + id + "/cancel", "{}").andExpect(status().isOk());
		assertThat(lead(first)).isEqualTo(MAREK);
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM substitution_request WHERE status='APPROVED'", Integer.class)).isEqualTo(2);
	}

	@Test
	void aConflictInTheSecondSessionRollsBackTheEntireBatch() throws Exception {
		qualify();
		String first = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String second = createEvent(poleClass(this.monday, "12:00", "13:00"));
		String id = request(List.of(first, second));
		approve(id, 0).andExpect(status().isOk());
		String input = batch(id, 1, true);
		createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday, "12:00"), at(this.monday, "13:00"),
				staff(MAREK, "LEAD"), room(GYM, true)));
		post(ADMIN_TOKEN, path(id) + "/assign", input).andExpect(status().isConflict());
		assertThat(lead(first)).isEqualTo(ANNA);
		assertThat(lead(second)).isEqualTo(ANNA);
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM substitution_request WHERE status='OPEN'", Integer.class)).isEqualTo(2);
	}

	@Test
	void approvesMultipleClaimsAndEnforcesAdministratorRole() throws Exception {
		qualify();
		String first = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String second = createEvent(poleClass(this.monday, "12:00", "13:00"));
		String id = request(List.of(first, second));
		approve(id, 0).andExpect(status().isOk());
		for (String offer : this.jdbcTemplate.queryForList("SELECT id::text FROM substitution_request", String.class)) {
			post(MAREK_TOKEN, "/api/v1/me/substitution-offers/" + offer + "/claim", "{\"version\":0}").andExpect(status().isOk());
		}
		String input = batch(id, 1, false);
		post(ANNA_TOKEN, path(id) + "/approve", input).andExpect(status().isForbidden());
		post(ADMIN_TOKEN, path(id) + "/approve", input).andExpect(status().isOk());
		assertThat(lead(first)).isEqualTo(MAREK);
		assertThat(lead(second)).isEqualTo(MAREK);
	}

	private String request(List<String> ids) throws Exception {
		return JsonPath.read(post(ANNA_TOKEN, "/api/v1/me/time-off", absenceBody(ids)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(), "$.id");
	}

	@Test
	void automaticClassCancellationAlsoClosesItsOutstandingOffers() throws Exception {
		String event = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String absence = request(List.of(event));
		approve(absence, 0).andExpect(status().isOk());
		this.transactions.executeWithoutResult(transaction -> {
			this.jdbcTemplate.update("UPDATE schedule_event SET status='CANCELLED' WHERE id=?", UUID.fromString(event));
			this.publisher.publishEvent(new studioforyou.pl.backend.scheduling.ScheduleEventCancelled(UUID.fromString(event),
				"CLASS", "Pole", at(this.monday,"10:00"), at(this.monday,"11:00"), List.of(),
				studioforyou.pl.backend.scheduling.ScheduleEventCancelled.Reason.TOO_FEW_PARTICIPANTS,
				java.util.Set.of(), java.util.Set.of(ANNA), null, 0, 2));
		});
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM substitution_request WHERE event_id=?", String.class,
			UUID.fromString(event))).isEqualTo("CANCELLED");
	}

	@Test
	void movingOutsideLeaveExpiresItsOfferButKeepsAnIndependentRequest() throws Exception {
		qualify();
		String first = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String second = createEvent(poleClass(this.monday, "12:00", "13:00"));
		post(ANNA_TOKEN, "/api/v1/me/schedule-events/" + second + "/substitution-request", "{}").andExpect(status().isCreated());
		String absence = request(List.of(first));
		approve(absence, 0).andExpect(status().isOk());
		for (String event : List.of(first, second)) {
			String hour = event.equals(first) ? "10:00" : "12:00";
			String end = event.equals(first) ? "11:00" : "13:00";
			String json = "{\"version\":0,\"startsAt\":\"" + at(this.monday.plusDays(1), hour)
					+ "\",\"endsAt\":\"" + at(this.monday.plusDays(1), end) + "\",\"capacity\":8,\"staff\":["
					+ staff(ANNA, "LEAD") + "],\"resources\":[" + room(POLE_ROOM, true) + "]}";
			this.mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/schedule-events/" + event)
				.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
		}
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM substitution_request WHERE event_id=?", String.class, UUID.fromString(first))).isEqualTo("EXPIRED");
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM substitution_request WHERE event_id=?", String.class, UUID.fromString(second))).isEqualTo("OPEN");
		String offer = this.jdbcTemplate.queryForObject("SELECT id::text FROM substitution_request WHERE event_id=?", String.class, UUID.fromString(first));
		post(MAREK_TOKEN, "/api/v1/me/substitution-offers/" + offer + "/claim", "{\"version\":0}").andExpect(status().isConflict());
	}

	@Test
	void consultationsRequireManualDecisionAndCannotBecomeSubstitutionOffers() throws Exception {
		String lesson = createEvent(poleClass(this.monday, "10:00", "11:00"));
		String consultation = JsonPath.read(post(ANNA_TOKEN, "/api/v1/me/schedule-events/consultations",
				"{\"resourceId\":\"" + GYM + "\",\"startsAt\":\"" + at(this.monday, "12:00")
				+ "\",\"endsAt\":\"" + at(this.monday, "13:00") + "\"}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
		post(ANNA_TOKEN, "/api/v1/me/time-off", absenceBody(List.of(consultation))).andExpect(status().isConflict());
		String absence = request(List.of(lesson));
		get(absence).andExpect(jsonPath("$.items[1].status").value("MANUAL"))
			.andExpect(jsonPath("$.items[1].selectable").value(false)).andExpect(jsonPath("$.items[1].selected").value(false));
		approve(absence, 0).andExpect(status().isOk());
		assertThat(countRequests()).isEqualTo(1);
		assertThat(lead(consultation)).isEqualTo(ANNA);
	}

	private String absenceBody(List<String> ids) {
		return "{\"kind\":\"VACATION\",\"startsAt\":\"" + at(this.monday, "00:00") + "\",\"endsAt\":\""
			+ at(this.monday.plusDays(1), "00:00") + "\",\"substitutionEventIds\":["
			+ ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]}";
	}

	private ResultActions approve(String id, int version) throws Exception {
		return post(ADMIN_TOKEN, "/api/v1/staff/time-off/" + id + "/approve", "{\"version\":" + version + "}");
	}

	private ResultActions get(String id) throws Exception {
		return this.mockMvc.perform(MockMvcRequestBuilders.get(path(id)));
	}

	private String batch(String id, int version, boolean assign) throws Exception {
		List<Map<String, Object>> rows = JsonPath.read(get(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items");
		String items = rows.stream().map(row -> "{\"eventId\":\"" + row.get("eventId") + "\",\"eventVersion\":" + row.get("eventVersion")
			+ ",\"requestId\":" + (row.get("requestId") == null ? "null" : "\"" + row.get("requestId") + "\"")
			+ ",\"requestVersion\":" + row.get("requestVersion") + "}").collect(Collectors.joining(","));
		return "{\"version\":" + version + ",\"items\":[" + items + "]" + (assign ? ",\"staffId\":\"" + MAREK + "\"" : "") + "}";
	}

	private ResultActions post(String token, String path, String body) throws Exception {
		return this.mockMvc.perform(MockMvcRequestBuilders.post(path).header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String path(String id) { return "/api/v1/staff/time-off/" + id + "/substitutions"; }
	private int countRequests() { return this.jdbcTemplate.queryForObject("SELECT count(*) FROM substitution_request", Integer.class); }
	private UUID lead(String event) { return this.jdbcTemplate.queryForObject("SELECT staff_id FROM event_staff WHERE event_id=? AND role='LEAD'", UUID.class, UUID.fromString(event)); }
	private void qualify() { this.jdbcTemplate.update("INSERT INTO staff_qualification(staff_id,service_type_id) VALUES (?,?)", MAREK, POLE); }
}
