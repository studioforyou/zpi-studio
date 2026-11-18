package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.Effect;
import studioforyou.pl.backend.shared.api.ApiException;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PersonalTrainingActionsTests {
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired PersonalTrainingService service;
	@MockitoBean JwtDecoder decoder;
	UUID actor, outsider, profile, child, otherProfile, offering, variant, policy, type, pass, event, reservation;
	UUID studioId;
	Instant startsAt;

	@BeforeEach
	void setUp() {
		studioId=jdbc.sql("INSERT INTO studio (name) VALUES ('pt-actions') RETURNING id").query(UUID.class).single();
		actor = account("owner"); outsider = account("outsider");
		profile = profile(actor, "ADULT"); child = profile(null, "CHILD"); otherProfile = profile(outsider, "ADULT");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:parent,:child)").param("parent",profile).param("child",child).update();
		type = jdbc.sql("INSERT INTO service_type (name,category,default_duration_minutes,max_participants) VALUES ('pt-actions','PERSONAL_TRAINING',60,1) RETURNING id").query(UUID.class).single();
		policy = jdbc.sql("INSERT INTO makeup_policy (name,validity_days,max_per_pass) VALUES ('pt-actions',30,2) RETURNING id").query(UUID.class).single();
		offering = jdbc.sql("INSERT INTO pass_product (name,kind,audience,makeup_policy_id) VALUES ('pt-actions','PT_PACKAGE','ANY',:policy) RETURNING id").param("policy",policy).query(UUID.class).single();
		variant = jdbc.sql("INSERT INTO pass_product_variant (product_id,entries_count,validity_days,price) VALUES (:p,8,60,500) RETURNING id").param("p",offering).query(UUID.class).single();
		jdbc.sql("INSERT INTO pass_product_service (product_id,service_type_id) VALUES (:p,:s)").param("p",offering).param("s",type).update();
		pass = jdbc.sql("INSERT INTO customer_pass (customer_id,variant_id,product_name,entries_total,entries_remaining,price_paid,status,valid_from,valid_until) VALUES (:customer,:variant,'PT test',8,7,500,'ACTIVE',CURRENT_DATE,CURRENT_DATE+60) RETURNING id").param("customer",profile).param("variant",variant).query(UUID.class).single();
		startsAt=Instant.now().plusSeconds(3*86400);
		event=event(startsAt); reservation=reservation(event);
		jdbc.sql("INSERT INTO pass_ledger (pass_id,kind,entries_delta) VALUES (:pass,'ACTIVATION',8)").param("pass",pass).update();
		allocate(reservation);
	}

	@AfterEach
	void cleanup() {
		jdbc.sql("DELETE FROM studio WHERE id=:id").param("id",studioId).update();
		String sessions="SELECT id FROM schedule_event WHERE service_type_id=:type";
		String bookings="SELECT id FROM reservation WHERE event_id IN ("+sessions+")";
		jdbc.sql("DELETE FROM audit_log WHERE entity_id IN ("+bookings+") OR entity_id IN ("+sessions+")").param("type",type).update();
		jdbc.sql("DELETE FROM training_dispute WHERE reservation_id IN ("+bookings+")").param("type",type).update();
		jdbc.sql("UPDATE reservation SET makeup_credit_id=NULL WHERE event_id IN ("+sessions+")").param("type",type).update();
		jdbc.sql("DELETE FROM makeup_credit WHERE source_pass_id=:pass").param("pass",pass).update();
		jdbc.sql("DELETE FROM pass_ledger WHERE pass_id=:pass").param("pass",pass).update();
		jdbc.sql("DELETE FROM reservation WHERE event_id IN ("+sessions+")").param("type",type).update();
		jdbc.sql("DELETE FROM schedule_event WHERE service_type_id=:type").param("type",type).update();
		jdbc.sql("DELETE FROM customer_pass WHERE id=:pass").param("pass",pass).update();
		jdbc.sql("DELETE FROM pass_product_service WHERE product_id=:product").param("product",offering).update();
		jdbc.sql("DELETE FROM pass_product_variant WHERE id=:variant").param("variant",variant).update();
		jdbc.sql("DELETE FROM pass_product WHERE id=:product").param("product",offering).update();
		jdbc.sql("DELETE FROM makeup_policy WHERE id=:policy").param("policy",policy).update();
		jdbc.sql("DELETE FROM service_type WHERE id=:type").param("type",type).update();
		jdbc.sql("DELETE FROM guardianship WHERE guardian_id=:id").param("id",profile).update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)").param("ids",List.of(profile,child,otherProfile)).update();
		for(String table:List.of("account_role","identity_link")) jdbc.sql("DELETE FROM "+table+" WHERE account_id IN (:ids)").param("ids",List.of(actor,outsider)).update();
		jdbc.sql("DELETE FROM account WHERE id IN (:ids)").param("ids",List.of(actor,outsider)).update();
	}

	@Test
	void anInTimeCancellationConvertsTheAllocatedEntryToOneMakeupWithoutRefundingOrChargingAgain() throws Exception {
		detail(profile).andExpect(jsonPath("$.cancellation.effect").value("MAKEUP_GRANTED")).andExpect(jsonPath("$.cancellation.validityDays").value(30));
		cancel("MAKEUP_GRANTED",30,0).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancellationEffect").value("MAKEUP_GRANTED"));
		cancel("MAKEUP_GRANTED",30,0).andExpect(status().isOk());
		assertThat(credits()).isEqualTo(1);
		assertThat(balance()).isEqualTo(7);
		assertThat(count("SELECT count(*) FROM pass_ledger WHERE pass_id=:pass AND kind='ENTRY_CONSUMED'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM pass_ledger WHERE pass_id=:pass AND kind='ENTRY_RETURNED'")).isZero();
		assertThat(jdbc.sql("SELECT status FROM schedule_event WHERE id=:id").param("id",event).query(String.class).single()).isEqualTo("CANCELLED");
	}

	@Test
	void theTrainerIsToldWhenTheClientCancelsTheTraining() throws Exception {
		UUID trainerAccount=jdbc.sql("INSERT INTO account (email,status) VALUES (:email,'ACTIVE') RETURNING id").param("email","pt-actions-trainer-"+UUID.randomUUID()+"@example.test").query(UUID.class).single();
		UUID staff=jdbc.sql("INSERT INTO staff_profile (account_id,first_name,last_name,calendar_color,status) VALUES (:account,'Iga','Trener','#E91E63','ACTIVE') RETURNING id").param("account",trainerAccount).query(UUID.class).single();
		jdbc.sql("INSERT INTO event_staff (event_id,staff_id,role) VALUES (:event,:staff,'LEAD')").param("event",event).param("staff",staff).update();
		try {
			cancel("MAKEUP_GRANTED",30,0).andExpect(status().isOk());
			String trainerNotices="SELECT n.title || ' / ' || n.body FROM notification n JOIN notification_type t ON t.id=n.notification_type_id WHERE n.recipient_id=:id AND t.code='CLASS_CANCELLED'";
			Instant deadline=Instant.now().plusSeconds(10);
			while(jdbc.sql(trainerNotices).param("id",trainerAccount).query(String.class).list().isEmpty() && Instant.now().isBefore(deadline)) Thread.sleep(50);
			assertThat(jdbc.sql(trainerNotices).param("id",trainerAccount).query(String.class).single())
				.startsWith("Zajęcia odwołane / Odwołano: Personal session, ").endsWith("Powód: rezygnacja klienta (PT Tester).");
		}
		finally {
			studioforyou.pl.backend.NotificationCleanup.deleteNotifications(jdbc,"SELECT '"+trainerAccount+"'::uuid");
			jdbc.sql("DELETE FROM event_staff WHERE staff_id=:staff").param("staff",staff).update();
			jdbc.sql("DELETE FROM staff_profile WHERE id=:staff").param("staff",staff).update();
			jdbc.sql("DELETE FROM account WHERE id=:id").param("id",trainerAccount).update();
		}
	}

	@Test
	void lateCancellationAndAChangedQuoteCannotSilentlyGrantOrSpendAnotherEntry() throws Exception {
		jdbc.sql("UPDATE reservation SET free_cancel_until=now()-interval '1 hour' WHERE id=:id").param("id",reservation).update();
		cancel("MAKEUP_GRANTED",30,0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_CONSEQUENCE_CHANGED"));
		cancel("ENTRY_FORFEITED",null,9).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		cancel("ENTRY_FORFEITED",null,0).andExpect(status().isOk());
		assertThat(credits()).isZero(); assertThat(balance()).isEqualTo(7);
	}

	@Test
	void studioPolicyAndProductGrantLimitsAreRecheckedBeforeConfirmation() throws Exception {
		jdbc.sql("UPDATE makeup_policy SET max_per_pass=0 WHERE id=:id").param("id",policy).update();
		detail(profile).andExpect(jsonPath("$.cancellation.consequenceReason").value("MAKEUP_LIMIT_REACHED"));
		cancel("MAKEUP_GRANTED",30,0).andExpect(status().isConflict());
		assertThat(credits()).isZero(); assertThat(balance()).isEqualTo(7);
		jdbc.sql("UPDATE makeup_policy SET max_per_pass=NULL, grant_on_in_time_cancel=FALSE WHERE id=:id").param("id",policy).update();
		detail(profile).andExpect(jsonPath("$.cancellation.consequenceReason").value("MAKEUP_NOT_GRANTED"));
	}

	@Test
	void anUnlimitedPackageNeverCreatesMakeupCredits() throws Exception {
		jdbc.sql("UPDATE customer_pass SET entries_total=NULL, entries_remaining=NULL WHERE id=:id").param("id",pass).update();
		jdbc.sql("UPDATE pass_ledger SET entries_delta=0 WHERE pass_id=:pass").param("pass",pass).update();
		cancel("NO_ENTRY",null,0).andExpect(status().isOk()).andExpect(jsonPath("$.cancellationEffect").value("NO_ENTRY"));
		assertThat(credits()).isZero();
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id=:id").param("id",pass).query().singleRow().get("entries_remaining")).isNull();
	}

	@Test
	void aSessionWithoutFundingCancelsWithoutTouchingAnyPackage() throws Exception {
		jdbc.sql("DELETE FROM pass_ledger WHERE reservation_id=:id").param("id",reservation).update();
		jdbc.sql("UPDATE reservation SET customer_pass_id=NULL WHERE id=:id").param("id",reservation).update();
		cancel("NO_ENTRY",null,0).andExpect(status().isOk());
		assertThat(credits()).isZero(); assertThat(balance()).isEqualTo(7);
	}

	@Test
	void cancellationReturnsTheSameUsedMakeupWithItsOriginalExpiry() throws Exception {
		UUID credit=useMakeup(false);
		var expiry=jdbc.sql("SELECT expires_at FROM makeup_credit WHERE id=:id").param("id",credit).query(OffsetDateTime.class).single();
		cancel("MAKEUP_RETURNED",null,0).andExpect(status().isOk());
		assertThat(credits()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id=:id").param("id",credit).query(String.class).single()).isEqualTo("AVAILABLE");
		assertThat(jdbc.sql("SELECT expires_at FROM makeup_credit WHERE id=:id").param("id",credit).query(OffsetDateTime.class).single()).isEqualTo(expiry);
	}

	@Test
	void expiredMakeupCannotBeRenewedByCancelling() throws Exception {
		UUID credit=useMakeup(true);
		cancel("MAKEUP_RETURNED",null,0).andExpect(status().isConflict());
		cancel("ENTRY_FORFEITED",null,0).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT status FROM makeup_credit WHERE id=:id").param("id",credit).query(String.class).single()).isEqualTo("USED");
	}

	@Test
	void aDisputeIsIdempotentAndLeavesPackageAccountingForTheAdministrator() throws Exception {
		confirm(2);
		detail(profile).andExpect(jsonPath("$.disputeAvailability.allowed").value(true));
		long before=count("SELECT count(*) FROM pass_ledger WHERE pass_id=:pass");
		raise("Trening nie odbył się.").andExpect(status().isOk()).andExpect(jsonPath("$.settlementStatus").value("DISPUTED"))
			.andExpect(jsonPath("$.dispute.status").value("OPEN"));
		raise("Trening nie odbył się.").andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT count(*) FROM training_dispute WHERE reservation_id=:id").param("id",reservation).query(Long.class).single()).isEqualTo(1);
		assertThat(balance()).isEqualTo(7); assertThat(credits()).isZero();
		assertThat(count("SELECT count(*) FROM pass_ledger WHERE pass_id=:pass")).isEqualTo(before);
	}

	@Test
	void reportingRequiresTrainerConfirmationAndClosesAfterTheStudiosWindow() throws Exception {
		raise("Brak treningu").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PERSONAL_SESSION_NOT_CONFIRMED"));
		confirm(25);
		raise("Brak treningu").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DISPUTE_WINDOW_CLOSED"));
		jdbc.sql("UPDATE studio SET pt_dispute_window_hours=48").update();
		try { detail(profile).andExpect(jsonPath("$.disputeAvailability.allowed").value(true)); }
		finally { jdbc.sql("UPDATE studio SET pt_dispute_window_hours=24").update(); }
	}

	@Test
	void anUnrelatedAccountCannotReadOrChangeAChildsSessionAndGuardianshipIsCheckedAgain() throws Exception {
		jdbc.sql("UPDATE reservation SET customer_id=:child WHERE id=:id").param("child",child).param("id",reservation).update();
		jdbc.sql("UPDATE customer_pass SET customer_id=:child WHERE id=:id").param("child",child).param("id",pass).update();
		detail(child).andExpect(status().isOk());
		mvc.perform(get(path(child)).header("Authorization","Bearer outsider")).andExpect(status().isNotFound());
		jdbc.sql("UPDATE guardianship SET ended_at=now() WHERE child_id=:id").param("id",child).update();
		detail(child).andExpect(status().isNotFound());
		mvc.perform(get(path(profile))).andExpect(status().isUnauthorized());
	}

	@Test
	void groupClassesAreNotAccessibleThroughPersonalTrainingActions() throws Exception {
		jdbc.sql("UPDATE schedule_event SET kind='CLASS' WHERE id=:id").param("id",event).update();
		detail(profile).andExpect(status().isNotFound());
		cancel("MAKEUP_GRANTED",30,0).andExpect(status().isNotFound());
		assertThat(credits()).isZero();
	}

	@Test
	void competingCancellationsCannotExceedThePackagesMakeupLimit() throws Exception {
		jdbc.sql("UPDATE makeup_policy SET max_per_pass=1 WHERE id=:id").param("id",policy).update();
		UUID second=reservation(event(startsAt.plusSeconds(7200))); allocate(second);
		jdbc.sql("UPDATE customer_pass SET entries_remaining=6 WHERE id=:id").param("id",pass).update();
		var results=compete(()->cancelDirect(reservation),()->cancelDirect(second));
		assertThat(results).containsExactlyInAnyOrder("OK","CANCELLATION_CONSEQUENCE_CHANGED");
		assertThat(credits()).isEqualTo(1); assertThat(balance()).isEqualTo(6);
	}

	@Test
	void personalTrainingAppearsInExistingHistoryWithItsRealCancellationResult() throws Exception {
		cancel("MAKEUP_GRANTED",30,0).andExpect(status().isOk());
		jdbc.sql("UPDATE schedule_event SET starts_at=now()-interval '2 hours', ends_at=now()-interval '1 hour' WHERE id=:id").param("id",event).update();
		mvc.perform(get("/api/v1/me/profiles/"+profile+"/training-history").header("Authorization","Bearer owner")
			.param("from",Instant.now().minusSeconds(86400).toString()).param("to",Instant.now().plusSeconds(86400).toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].cancellationEffect").value("MAKEUP_GRANTED"));
	}

	@Test
	void anotherAccountCannotCancelOrDisputeSomebodyElsesReservation() throws Exception {
		mvc.perform(post(path(profile)+"/cancel").header("Authorization","Bearer outsider").contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"expectedEffect\":\"MAKEUP_GRANTED\",\"expectedValidityDays\":30}"))
			.andExpect(status().isNotFound());
		confirm(2);
		mvc.perform(post(path(profile)+"/disputes").header("Authorization","Bearer outsider").contentType(MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"reason\":\"Nie było treningu\"}")).andExpect(status().isNotFound());
		assertThat(credits()).isZero();
	}

	@Test
	void aDisputeRequiresANonemptyReason() throws Exception {
		confirm(2);
		raise("   ").andExpect(status().isBadRequest());
		assertThat(jdbc.sql("SELECT count(*) FROM training_dispute WHERE reservation_id=:id").param("id",reservation).query(Long.class).single()).isZero();
	}

	private UUID useMakeup(boolean expired) {
		return attachMakeup(expired);
	}

	@Test
	void finalityIsDerivedAtTheWindowBoundaryWithoutHidingAnOpenDispute() {
		Instant now=Instant.parse("2026-11-08T12:00:00Z");
		Instant confirmed=now.minusSeconds(86400);
		assertThat(PersonalTrainingService.effectiveSettlement("OPEN",confirmed,confirmed,false,now.minusNanos(1),24)).isEqualTo("OPEN");
		assertThat(PersonalTrainingService.effectiveSettlement("OPEN",confirmed,confirmed,false,now,24)).isEqualTo("FINAL");
		assertThat(PersonalTrainingService.effectiveSettlement("OPEN",confirmed,confirmed,true,now,24)).isEqualTo("DISPUTED");
		assertThat(PersonalTrainingService.effectiveSettlement("OPEN",null,confirmed,false,now,24)).isEqualTo("OPEN");
	}

	@Test
	void aClosedUndisputedWindowHasTheSameFinalStatusForTheClientWithoutChangingTheBalance() throws Exception {
		confirm(25);
		detail(profile).andExpect(jsonPath("$.settlementStatus").value("FINAL"))
			.andExpect(jsonPath("$.disputeAvailability.reason").value("DISPUTE_WINDOW_CLOSED"));
		assertThat(balance()).isEqualTo(7);assertThat(credits()).isZero();
	}

	private UUID attachMakeup(boolean expired) {
		jdbc.sql("DELETE FROM pass_ledger WHERE reservation_id=:id").param("id",reservation).update();
		UUID credit=jdbc.sql("INSERT INTO makeup_credit (customer_id,source_pass_id,status,used_at,used_reservation_id,expires_at) VALUES (:customer,:pass,'USED',now(),:res,:expiry) RETURNING id")
			.param("customer",profile).param("pass",pass).param("res",reservation).param("expiry",utc(Instant.now().plusSeconds(expired?-60:9*86400))).query(UUID.class).single();
		jdbc.sql("UPDATE reservation SET customer_pass_id=NULL, makeup_credit_id=:credit WHERE id=:id").param("credit",credit).param("id",reservation).update();
		return credit;
	}
	private void confirm(int hoursAgo) {
		jdbc.sql("UPDATE schedule_event SET starts_at=:start, ends_at=:end, status='COMPLETED' WHERE id=:id")
			.param("start",utc(Instant.now().minusSeconds((hoursAgo+2)*3600L))).param("end",utc(Instant.now().minusSeconds((hoursAgo+1)*3600L))).param("id",event).update();
		jdbc.sql("UPDATE reservation SET trainer_confirmed_at=:time, attendance='PRESENT' WHERE id=:id")
			.param("time",utc(Instant.now().minusSeconds(hoursAgo*3600L))).param("id",reservation).update();
	}
	private UUID account(String token) {
		String uid="pt-actions-"+UUID.randomUUID();
		UUID id=jdbc.sql("INSERT INTO account (email,status) VALUES (:email,'ACTIVE') RETURNING id").param("email",uid+"@example.test").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id,provider,external_uid) VALUES (:id,'FIREBASE',:uid)").param("id",id).param("uid",uid).update();
		jdbc.sql("INSERT INTO account_role (account_id,role_id) SELECT :id,id FROM role WHERE code='CLIENT'").param("id",id).update();
		given(decoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg","RS256").subject(uid).build());
		return id;
	}
	private UUID profile(UUID account,String kind) {
		return jdbc.sql("INSERT INTO customer_profile (account_id,kind,first_name,last_name) VALUES (:account,:kind,'PT','Tester') RETURNING id")
			.param("account",account,java.sql.Types.OTHER).param("kind",kind).query(UUID.class).single();
	}
	private UUID event(Instant start) {
		return jdbc.sql("INSERT INTO schedule_event (kind,service_type_id,title,starts_at,ends_at,capacity,visibility) VALUES ('PERSONAL_TRAINING',:type,'Personal session',:start,:end,1,'PRIVATE') RETURNING id")
			.param("type",type).param("start",utc(start)).param("end",utc(start.plusSeconds(3600))).query(UUID.class).single();
	}
	private UUID reservation(UUID eventId) {
		return jdbc.sql("INSERT INTO reservation (event_id,customer_id,customer_pass_id,booked_by,free_cancel_until,cancellation_allowed_snapshot,late_cancel_allowed_snapshot,late_cancel_forfeits_entry_snapshot) VALUES (:event,:customer,:pass,:actor,:deadline,TRUE,TRUE,TRUE) RETURNING id")
			.param("event",eventId).param("customer",profile).param("pass",pass).param("actor",actor).param("deadline",utc(startsAt.minusSeconds(86400))).query(UUID.class).single();
	}
	private void allocate(UUID booking) { jdbc.sql("INSERT INTO pass_ledger (pass_id,reservation_id,kind,entries_delta) VALUES (:pass,:booking,'ENTRY_RESERVED',-1)").param("pass",pass).param("booking",booking).update(); }
	private int balance() { return jdbc.sql("SELECT entries_remaining::INT FROM customer_pass WHERE id=:id").param("id",pass).query(Integer.class).single(); }
	private long count(String sql) { return jdbc.sql(sql).param("pass",pass).query(Long.class).single(); }
	private long credits() { return count("SELECT count(*) FROM makeup_credit WHERE source_pass_id=:pass"); }
	private String path(UUID customer) { return "/api/v1/me/profiles/"+customer+"/personal-trainings/"+reservation; }
	private ResultActions detail(UUID customer) throws Exception { return mvc.perform(get(path(customer)).header("Authorization","Bearer owner")); }
	private ResultActions cancel(String effect,Integer days,int version) throws Exception { return mvc.perform(post(path(profile)+"/cancel").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+version+",\"expectedEffect\":\""+effect+"\",\"expectedValidityDays\":"+days+"}")); }
	private ResultActions raise(String reason) throws Exception { return mvc.perform(post(path(profile)+"/disputes").header("Authorization","Bearer owner").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"reason\":\""+reason+"\"}")); }
	private String cancelDirect(UUID booking) { try { service.cancel(actor,profile,booking,new PersonalTrainingDtos.CancelRequest(0,Effect.MAKEUP_GRANTED,30)); return "OK"; } catch(ApiException error) { return error.getErrorCode().name(); } }
	private List<String> compete(Callable<String> first,Callable<String> second) throws Exception {
		try(var pool=Executors.newFixedThreadPool(2)) {
			var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
			var a=pool.submit(()->{ready.countDown();go.await();return first.call();});
			var b=pool.submit(()->{ready.countDown();go.await();return second.call();});
			assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
			return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
		}
	}
	private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
