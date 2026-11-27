package studioforyou.pl.backend;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** Real schedule assignments, periods and access rules behind the worked-hours report. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestcontainersConfiguration.class)
class TrainerHoursReportTests {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcClient jdbc;
	@MockitoBean private JwtDecoder jwtDecoder;
	private UUID admin;
	private UUID anna;
	private UUID marek;
	private UUID room;

	@BeforeEach
	void setUp() {
		admin = account("ADMIN", "hours-admin");
		anna = instructor("Anna", "Kowalska", account("TRAINER", "hours-trainer"));
		marek = instructor("Marek", "Nowak", account("TRAINER", "hours-marek"));
		room = jdbc.sql("INSERT INTO resource(name,kind) VALUES ('Sala raportu','ROOM') RETURNING id").query(UUID.class).single();
	}

	@Test
	void sumsLeadingAndAssistingOnceEvenWhenAnEventUsesTwoRooms() throws Exception {
		UUID event = event("CLASS", "2025-03-10T17:00:00Z", "2025-03-10T18:15:00Z", "COMPLETED");
		assign(event, anna, "LEAD");
		assign(event, marek, "ASSISTANT");
		UUID secondRoom = jdbc.sql("INSERT INTO resource(name,kind) VALUES ('Druga sala','ROOM') RETURNING id").query(UUID.class).single();
		jdbc.sql("INSERT INTO event_resource(event_id,resource_id) VALUES (:event,:room)").param("event",event).param("room",secondRoom).update();
		report("").andExpect(status().isOk())
			.andExpect(jsonPath("$.totals.seconds").value(9000))
			.andExpect(jsonPath("$.totals.leadSeconds").value(4500))
			.andExpect(jsonPath("$.totals.assistantSeconds").value(4500))
			.andExpect(jsonPath("$.totals.events").value(1))
			.andExpect(jsonPath(path(anna,"counts.seconds")).value(contains(4500)))
			.andExpect(jsonPath(path(marek,"sessions[0].role")).value(contains("ASSISTANT")))
			.andExpect(jsonPath(path(anna,"sessions[0].rooms")).value(contains("Druga sala, Sala raportu")));
	}

	@Test
	void includesUnconfirmedConsultationsAndCanRestrictToConfirmedOrOneKindAndInstructor() throws Exception {
		assign(event("CONSULTATION","2025-03-11T08:00:00Z","2025-03-11T08:30:00Z","SCHEDULED"),anna,"LEAD");
		assign(event("PERSONAL_TRAINING","2025-03-12T08:00:00Z","2025-03-12T08:45:00Z","COMPLETED"),marek,"LEAD");
		report("").andExpect(status().isOk()).andExpect(jsonPath("$.totals.seconds").value(4500))
			.andExpect(jsonPath("$.totals.unconfirmedSeconds").value(1800))
			.andExpect(jsonPath("$.totals.unconfirmedEvents").value(1));
		report("&confirmedOnly=true").andExpect(status().isOk()).andExpect(jsonPath("$.totals.seconds").value(2700))
			.andExpect(jsonPath("$.totals.unconfirmedSeconds").value(0));
		report("&kind=CONSULTATION&staffId="+anna).andExpect(status().isOk())
			.andExpect(jsonPath("$.instructors",hasSize(1))).andExpect(jsonPath("$.totals.seconds").value(1800))
			.andExpect(jsonPath("$.instructors[0].sessions[0].confirmed").value(false));
		report("&kind=WORKSHOP").andExpect(status().isOk()).andExpect(jsonPath("$.totals.seconds").value(0));
	}

	@Test
	void excludesCancelledEventsBlocksUnassignedConsultationsAndUnfinishedEvents() throws Exception {
		assign(event("CLASS","2025-03-10T08:00:00Z","2025-03-10T09:00:00Z","CANCELLED"),anna,"LEAD");
		assign(event("BLOCK","2025-03-10T10:00:00Z","2025-03-10T11:00:00Z","COMPLETED"),anna,"LEAD");
		event("CONSULTATION","2025-03-10T12:00:00Z","2025-03-10T13:00:00Z","SCHEDULED");
		report("").andExpect(status().isOk()).andExpect(jsonPath("$.totals.events").value(0));
		Instant now=Instant.now();
		assign(event("CLASS",now.minusSeconds(300).toString(),now.plusSeconds(3300).toString(),"SCHEDULED"),anna,"LEAD");
		assign(event("CLASS",now.plusSeconds(7200).toString(),now.plusSeconds(10800).toString(),"COMPLETED"),anna,"LEAD");
		String today=LocalDate.now(ZoneId.of("Europe/Warsaw")).toString();
		request(today,today,"").andExpect(status().isOk()).andExpect(jsonPath("$.totals.seconds").value(0));
	}

	@Test
	void countsTheActualReplacementAndDoesNotTreatAPendingClaimAsAnAssignment() throws Exception {
		UUID replaced=event("PERSONAL_TRAINING","2025-03-12T08:00:00Z","2025-03-12T08:45:00Z","COMPLETED");
		assign(replaced,marek,"LEAD");
		UUID pending=event("CLASS","2025-03-13T08:00:00Z","2025-03-13T09:00:00Z","COMPLETED");
		assign(pending,anna,"LEAD");
		for (var entry : java.util.Map.of(replaced,"APPROVED",pending,"CLAIMED").entrySet()) {
			jdbc.sql("""
					INSERT INTO substitution_request(event_id,original_staff_id,requested_by,status,substitute_staff_id)
					VALUES (:event,:original,:admin,:status,:substitute)
					""").param("event",entry.getKey()).param("original",anna).param("admin",admin)
				.param("status",entry.getValue()).param("substitute",marek).update();
		}
		report("").andExpect(status().isOk())
			.andExpect(jsonPath(path(anna,"counts.seconds")).value(contains(3600)))
			.andExpect(jsonPath(path(marek,"counts.seconds")).value(contains(2700)));
	}

	@Test
	void usesInclusiveStudioDatesAndKeepsAnOvernightSessionsFullTimeOnItsStartDate() throws Exception {
		assign(event("CLASS","2025-03-09T23:00:00Z","2025-03-10T00:00:00Z","COMPLETED"),anna,"LEAD");
		assign(event("CLASS","2025-03-10T22:30:00Z","2025-03-11T00:30:00Z","COMPLETED"),anna,"LEAD");
		assign(event("CLASS","2025-03-10T23:00:00Z","2025-03-11T00:00:00Z","COMPLETED"),marek,"LEAD");
		request("2025-03-10","2025-03-10","").andExpect(status().isOk())
			.andExpect(jsonPath("$.timeZone").value("Europe/Warsaw"))
			.andExpect(jsonPath("$.totals.seconds").value(10800)).andExpect(jsonPath("$.totals.events").value(2));
	}

	@Test
	void usesElapsedTimeAcrossDaylightSavingAndDoesNotRoundIndividualSessions() throws Exception {
		assign(event("WORKSHOP","2025-03-30T00:30:00Z","2025-03-30T02:30:00Z","COMPLETED"),anna,"LEAD");
		assign(event("CLASS","2025-03-31T08:00:00Z","2025-03-31T08:01:30Z","COMPLETED"),anna,"LEAD");
		assign(event("CLASS","2025-03-31T09:00:00Z","2025-03-31T09:01:30Z","COMPLETED"),anna,"LEAD");
		report("").andExpect(status().isOk()).andExpect(jsonPath(path(anna,"counts.seconds")).value(contains(7380)));
	}

	@Test
	void keepsZeroHourInstructorsAndInactiveHistoricalStaffButNotAdministratorsWithoutClasses() throws Exception {
		UUID former=instructor("Były","Trener",account("CLIENT","hours-former"));
		jdbc.sql("UPDATE staff_profile SET status='INACTIVE' WHERE id=:id").param("id",former).update();
		assign(event("CLASS","2025-03-10T08:00:00Z","2025-03-10T09:00:00Z","COMPLETED"),former,"LEAD");
		instructor("Administrator","Bez zajęć",admin);
		report("").andExpect(status().isOk()).andExpect(jsonPath("$.instructors",hasSize(3)))
			.andExpect(jsonPath(path(anna,"counts.seconds")).value(contains(0)))
			.andExpect(jsonPath(path(former,"active")).value(contains(false)));
	}

	@Test
	void rejectsBadPeriodsKindsAndIds() throws Exception {
		request("2025-03-31","2025-03-01","").andExpect(status().isBadRequest());
		request("2020-01-01","2025-03-01","").andExpect(status().isBadRequest());
		report("&kind=BLOCK").andExpect(status().isBadRequest());
		report("&staffId=wrong").andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/reports/trainer-hours").header("Authorization","Bearer hours-admin")).andExpect(status().isBadRequest());
	}

	@Test
	void onlyAdministratorsCanReadHours() throws Exception {
		mvc.perform(get("/api/v1/reports/trainer-hours?from=2025-03-01&to=2025-03-31")).andExpect(status().isUnauthorized());
		for (String token : new String[] {"hours-trainer","hours-client"}) {
			if (token.equals("hours-client")) account("CLIENT",token);
			mvc.perform(get("/api/v1/reports/trainer-hours?from=2025-03-01&to=2025-03-31")
				.header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
		}
	}

	private ResultActions report(String filters) throws Exception { return request("2025-03-01","2025-03-31",filters); }
	private ResultActions request(String from,String to,String filters) throws Exception {
		return mvc.perform(get("/api/v1/reports/trainer-hours?from="+from+"&to="+to+filters).header("Authorization","Bearer hours-admin"));
	}
	private String path(UUID id,String suffix) { return "$.instructors[?(@.id=='"+id+"')]."+suffix; }
	private UUID account(String role,String token) {
		String uid=UUID.randomUUID().toString();
		UUID id=jdbc.sql("INSERT INTO account(email,status) VALUES (:email,'ACTIVE') RETURNING id").param("email",uid+"@hours.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO account_role(account_id,role_id) SELECT :id,id FROM role WHERE code=:role").param("id",id).param("role",role).update();
		jdbc.sql("INSERT INTO identity_link(account_id,provider,external_uid) VALUES (:id,'FIREBASE',:uid)").param("id",id).param("uid",uid).update();
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg","RS256").subject(uid).build());
		return id;
	}
	private UUID instructor(String first,String last,UUID account) {
		return jdbc.sql("INSERT INTO staff_profile(account_id,first_name,last_name) VALUES (:account,:first,:last) RETURNING id")
			.param("account",account).param("first",first).param("last",last).query(UUID.class).single();
	}
	private UUID event(String kind,String start,String end,String status) {
		UUID id=jdbc.sql("""
				INSERT INTO schedule_event(kind,title,starts_at,ends_at,status,visibility,created_by)
				VALUES (:kind,'Zajęcia raportu',:start,:end,:status,'PRIVATE',:admin) RETURNING id
				""").param("kind",kind).param("start",OffsetDateTime.parse(start)).param("end",OffsetDateTime.parse(end))
			.param("status",status).param("admin",admin).query(UUID.class).single();
		jdbc.sql("INSERT INTO event_resource(event_id,resource_id) VALUES (:event,:room)").param("event",id).param("room",room).update();
		return id;
	}
	private void assign(UUID event,UUID staff,String role) {
		jdbc.sql("INSERT INTO event_staff(event_id,staff_id,role) VALUES (:event,:staff,:role)")
			.param("event",event).param("staff",staff).param("role",role).update();
	}
}
