package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import studioforyou.pl.backend.communication.notification.Notifications;

class SubstitutionManagementTest extends ScheduleTestSupport {

	@MockitoBean Notifications notifications;
	private static final String OFFERS = "/api/v1/me/substitution-offers";
	private static final String ADMIN = "/api/v1/substitution-requests";

	@Test
	void claimWaitsForApprovalAndOnlyThenChangesThisOccurrence() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String request = request(event);
		qualify();
		list(MAREK_TOKEN, false).andExpect(jsonPath("$.items[0].canClaim").value(true))
			.andExpect(jsonPath("$.items[0].reason").doesNotExist());
		action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLAIMED")).andExpect(jsonPath("$.canWithdraw").value(true));
		assertThat(lead(event)).isEqualTo(ANNA);
		action(ADMIN_TOKEN, ADMIN, request, "approve", 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));
		assertThat(lead(event)).isEqualTo(MAREK);
		get("/api/v1/schedule-events/" + event + "/staff-history")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("SUBSTITUTION"))
			.andExpect(jsonPath("$.items[0].before[0].staffId").value(ANNA.toString()))
			.andExpect(jsonPath("$.items[0].after[0].staffId").value(MAREK.toString()));
		assertThat(this.jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE entity_id=?", Integer.class, UUID.fromString(request))).isEqualTo(2);
	}

	@Test
	void availableOffersExcludeOwnRequestsAndClaims() throws Exception {
		String request = request(createEvent(poleClass(this.monday, "18:00", "19:00")));
		qualify();
		list(ANNA_TOKEN, false).andExpect(jsonPath("$.items").isEmpty());
		list(ANNA_TOKEN, true).andExpect(jsonPath("$.items").isEmpty());
		list(MAREK_TOKEN, false).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(request));
		list(MAREK_TOKEN, true).andExpect(jsonPath("$.items").isEmpty());

		action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isOk());
		list(MAREK_TOKEN, false).andExpect(jsonPath("$.items").isEmpty());
		list(MAREK_TOKEN, true).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(request))
			.andExpect(jsonPath("$.items[0].status").value("CLAIMED"));
		list(ANNA_TOKEN, false).andExpect(jsonPath("$.items").isEmpty());

		action(ADMIN_TOKEN, ADMIN, request, "approve", 1).andExpect(status().isOk());
		list(MAREK_TOKEN, false).andExpect(jsonPath("$.items").isEmpty());
		list(MAREK_TOKEN, true).andExpect(jsonPath("$.items[0].status").value("APPROVED"));
	}

	@Test
	void rejectPreservesDecisionAndOpensANewOfferWithoutLosingTheOriginalReason() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String request = request(event); qualify();
		action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isOk());
		action(ADMIN_TOKEN, ADMIN, request, "reject", 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		list(MAREK_TOKEN, true).andExpect(jsonPath("$.items[0].status").value("REJECTED"));
		list(MAREK_TOKEN, false).andExpect(jsonPath("$.items[0].id").value(org.hamcrest.Matchers.not(request)));
		assertThat(this.jdbcTemplate.queryForObject("SELECT reason FROM substitution_request WHERE event_id=? AND status='OPEN'", String.class, UUID.fromString(event))).isEqualTo("Sprawa prywatna");
		assertThat(lead(event)).isEqualTo(ANNA);
	}

	@Test
	void candidateCanWithdrawOnlyTheirPendingClaim() throws Exception {
		String request = request(createEvent(poleClass(this.monday, "18:00", "19:00"))); qualify();
		action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isOk());
		action(ANNA_TOKEN, OFFERS, request, "withdraw", 1).andExpect(status().isNotFound());
		action(MAREK_TOKEN, OFFERS, request, "withdraw", 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		list(MAREK_TOKEN, false).andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void roleQualificationsAndOwnEventAreEnforcedOnServer() throws Exception {
		String request = request(createEvent(poleClass(this.monday, "18:00", "19:00")));
		action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_QUALIFIED"));
		action(ANNA_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("OWN_EVENT"));
		action(MAREK_TOKEN, ADMIN, request, "approve", 0).andExpect(status().isForbidden());
		this.anonymous.perform(MockMvcRequestBuilders.get(OFFERS + "/" + request)).andExpect(status().isUnauthorized());
	}

	@Test
	void checksTheScheduleAgainWhenAdminApproves() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00"));
		String request = request(event); qualify();
		action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isOk());
		createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday, "18:30"), at(this.monday, "19:30"), staff(MAREK, "LEAD"), room(GYM, true)));
		action(ADMIN_TOKEN, ADMIN, request, "approve", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSTITUTION_NOT_ELIGIBLE"));
		assertThat(lead(event)).isEqualTo(ANNA);
	}

	@Test
	void lateStaleAndInactiveOffersCannotBeClaimed() throws Exception {
		String event = createEvent(poleClass(this.monday, "18:00", "19:00")); String request = request(event); qualify();
		action(MAREK_TOKEN, OFFERS, request, "claim", 7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		this.jdbcTemplate.update("UPDATE schedule_event SET starts_at=now()-interval '2 hours', ends_at=now()-interval '1 hour' WHERE id=?", UUID.fromString(event));
		action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isConflict());
	}

	@Test
	void automaticApprovalHonorsTheStudioSetting() throws Exception {
		this.jdbcTemplate.update("INSERT INTO studio(id,name,substitution_needs_approval) VALUES (?,?,FALSE)", UUID.randomUUID(), "Test substitutions");
		try {
			String event=createEvent(poleClass(this.monday, "18:00", "19:00")); String request=request(event); qualify();
			action(MAREK_TOKEN, OFFERS, request, "claim", 0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
			assertThat(lead(event)).isEqualTo(MAREK);
			get("/api/v1/schedule-events/" + event + "/staff-history")
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].kind").value("AUTOMATIC_SUBSTITUTION"))
				.andExpect(jsonPath("$.items[0].actorName").value("Marek Nowak"));
		} finally { this.jdbcTemplate.update("DELETE FROM studio WHERE name='Test substitutions'"); }
	}

	@Test
	void simultaneousClaimsCannotBothWin() throws Exception {
		String request = request(createEvent(poleClass(this.monday,"18:00","19:00"))); qualify();
		CyclicBarrier start=new CyclicBarrier(2);
		try(var executor=Executors.newFixedThreadPool(2)) {
			var a=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return action(MAREK_TOKEN,OFFERS,request,"claim",0).andReturn().getResponse().getStatus();});
			var b=executor.submit(()->{start.await(10,TimeUnit.SECONDS);return action(MAREK_TOKEN,OFFERS,request,"claim",0).andReturn().getResponse().getStatus();});
			assertThat(List.of(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
		}
	}

	@Test
	void cancellationClosesTheRequestAndDoesNotInventPassEntriesForLegacyData() throws Exception {
		String event=createEvent(poleClass(this.monday,"18:00","19:00")); String request=request(event);
		this.mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/schedule-events/"+event+"/cancel")
			.contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"Brak zastępcy\"}"))
			.andExpect(status().isOk());
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM schedule_event WHERE id=?",String.class,UUID.fromString(event))).isEqualTo("CANCELLED");
		assertThat(this.jdbcTemplate.queryForObject("SELECT status FROM substitution_request WHERE id=?",String.class,UUID.fromString(request))).isEqualTo("CANCELLED");
	}

	private void qualify() { this.jdbcTemplate.update("INSERT INTO staff_qualification(staff_id,service_type_id) VALUES (?,?) ON CONFLICT DO NOTHING", MAREK, POLE); }
	private UUID lead(String event) { return this.jdbcTemplate.queryForObject("SELECT staff_id FROM event_staff WHERE event_id=? AND role='LEAD'",UUID.class,UUID.fromString(event)); }
	private String request(String event) throws Exception {
		return id(this.mockMvc.perform(as(ANNA_TOKEN,
				MockMvcRequestBuilders.post("/api/v1/me/schedule-events/" + event + "/substitution-request")
					.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Sprawa prywatna\"}")))
			.andExpect(status().isCreated()));
	}
	private ResultActions action(String token,String base,String id,String action,int version) throws Exception { return this.mockMvc.perform(as(token,MockMvcRequestBuilders.post(base+"/"+id+"/"+action).contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}"))); }
	private ResultActions list(String token,boolean mine) throws Exception { return this.mockMvc.perform(as(token,MockMvcRequestBuilders.get(OFFERS).param("from",at(this.monday,"00:00").toString()).param("to",at(this.monday.plusDays(1),"00:00").toString()).param("mine",Boolean.toString(mine)))); }
}
