package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.documents.DocumentRequirements;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;

/** Owner contracts used by booking, exercised against the real migrated schema. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ParticipationContractsTests {

	@Autowired JdbcClient jdbc;
	@Autowired DocumentRequirements documents;
	@Autowired GroupClassSchedule schedule;
	@Autowired BookingPolicies policies;
	@MockitoBean JwtDecoder jwtDecoder;

	private UUID service;
	private UUID profile;
	private UUID group;
	private UUID event;
	private final Instant start = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

	@BeforeEach
	void prepare() {
		service = jdbc.sql("""
				INSERT INTO service_type(name, category, default_duration_minutes)
				VALUES ('Contract class', 'GROUP', 60) RETURNING id
				""").query(UUID.class).single();
		profile = jdbc.sql("INSERT INTO customer_profile(first_name, last_name) VALUES ('Test', 'Participant') RETURNING id")
			.query(UUID.class).single();
		group = jdbc.sql("INSERT INTO training_group(name, service_type_id) VALUES ('Fixed group', :service) RETURNING id")
			.param("service", service).query(UUID.class).single();
		event = addEvent("CLASS", "PUBLIC");
	}

	@Test
	void onlyPublicClassesAreExposedByTheCustomerProjection() {
		UUID privateClass = addEvent("CLASS", "PRIVATE");
		UUID personal = addEvent("PERSONAL_TRAINING", "PUBLIC");
		UUID block = addEvent("BLOCK", "PUBLIC");
		assertThat(schedule.list(start.minusSeconds(1), start.plusSeconds(3601)))
			.extracting(GroupClassSchedule.Occurrence::id).containsExactly(event);
		assertThat(schedule.find(privateClass)).isEmpty();
		assertThat(schedule.find(personal)).isEmpty();
		assertThat(schedule.find(block)).isEmpty();
		assertThat(schedule.lock(event).capacity()).isEqualTo(8);
	}

	@Test
	void draftsDoNotBlockButPublishedRequiredDocumentsDo() {
		UUID document = addDocument("ALL");
		UUID version = addVersion(document, 1, true, false);
		assertThat(documents.hasMissing(profile, service, "ADULT")).isFalse();
		jdbc.sql("UPDATE document_version SET published_at = now() WHERE id = :id").param("id", version).update();
		assertThat(documents.hasMissing(profile, service, "ADULT")).isTrue();
		accept(version);
		assertThat(documents.hasMissing(profile, service, "ADULT")).isFalse();
		jdbc.sql("UPDATE document_acceptance SET revoked_at = now() WHERE customer_id = :id").param("id", profile).update();
		assertThat(documents.hasMissing(profile, service, "ADULT")).isTrue();
	}

	@Test
	void requiredReacceptanceInvalidatesAnOlderAcceptance() {
		UUID document = addDocument("ALL");
		accept(addVersion(document, 1, true, true));
		addVersion(document, 2, false, true);
		assertThat(documents.hasMissing(profile, service, "ADULT")).isFalse();
		UUID third = addVersion(document, 3, true, true);
		assertThat(documents.hasMissing(profile, service, "ADULT")).isTrue();
		accept(third);
		assertThat(documents.hasMissing(profile, service, "ADULT")).isFalse();
	}

	@Test
	void documentAudienceAndServiceScopeAreRespected() {
		UUID document = addDocument("CHILD");
		addVersion(document, 1, true, true);
		assertThat(documents.hasMissing(profile, service, "ADULT")).isFalse();
		assertThat(documents.hasMissing(profile, service, "CHILD")).isTrue();
		UUID anotherService = jdbc.sql("""
				INSERT INTO service_type(name, category, default_duration_minutes)
				VALUES ('Another service', 'GROUP', 60) RETURNING id
				""").query(UUID.class).single();
		jdbc.sql("INSERT INTO document_service_requirement(document_id, service_type_id) VALUES (:document, :service)")
			.param("document", document).param("service", anotherService).update();
		assertThat(documents.hasMissing(profile, service, "CHILD")).isFalse();
		assertThat(documents.hasMissing(profile, anotherService, "CHILD")).isTrue();
	}

	@Test
	void occurrencePolicyInheritsEachFieldThroughAllFourLayers() {
		jdbc.sql("INSERT INTO booking_policy(scope, free_cancel_hours, booking_opens_days_before) VALUES ('STUDIO', 24, 20)").update();
		jdbc.sql("INSERT INTO booking_policy(scope, service_type_id, free_cancel_hours) VALUES ('SERVICE_TYPE', :id, 12)")
			.param("id", service).update();
		jdbc.sql("INSERT INTO booking_policy(scope, training_group_id, booking_closes_minutes_before) VALUES ('GROUP', :id, 45)")
			.param("id", group).update();
		UUID mostSpecific = jdbc.sql("INSERT INTO booking_policy(scope, event_id, late_cancel_allowed) VALUES ('EVENT', :id, false) RETURNING id")
			.param("id", event).query(UUID.class).single();
		var policy = policies.forOccurrence(service, group, event);
		assertThat(policy.policyId()).isEqualTo(mostSpecific);
		assertThat(policy.effective().freeCancelHours()).isEqualTo(12);
		assertThat(policy.effective().bookingOpensDaysBefore()).isEqualTo(20);
		assertThat(policy.effective().bookingClosesMinutesBefore()).isEqualTo(45);
		assertThat(policy.effective().lateCancelAllowed()).isFalse();
	}

	private UUID addEvent(String kind, String visibility) {
		return jdbc.sql("""
				INSERT INTO schedule_event(kind, service_type_id, title, starts_at, ends_at, capacity, visibility)
				VALUES (:kind, :service, 'Group class', :start, :end, 8, :visibility) RETURNING id
				""").param("kind", kind).param("service", service).param("start", start.atOffset(java.time.ZoneOffset.UTC))
			.param("end", start.plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC)).param("visibility", visibility)
			.query(UUID.class).single();
	}

	private UUID addDocument(String audience) {
		return jdbc.sql("""
				INSERT INTO document(code, title, kind, audience, is_required, blocks_booking)
				VALUES (:code, 'Participation consent', 'PARTICIPATION_CONSENT', :audience, true, true) RETURNING id
				""").param("code", UUID.randomUUID().toString()).param("audience", audience).query(UUID.class).single();
	}

	private UUID addVersion(UUID document, int version, boolean requiresReacceptance, boolean published) {
		return jdbc.sql("""
				INSERT INTO document_version(document_id, version_no, requires_reacceptance, published_at)
				VALUES (:document, :version, :reaccept, CASE WHEN :published THEN now() END) RETURNING id
				""").param("document", document).param("version", version).param("reaccept", requiresReacceptance)
			.param("published", published).query(UUID.class).single();
	}

	private void accept(UUID version) {
		UUID actor = jdbc.sql("INSERT INTO account(email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", UUID.randomUUID() + "@example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO document_acceptance(document_version_id, customer_id, accepted_by) VALUES (:version, :profile, :actor)")
			.param("version", version).param("profile", profile).param("actor", actor).update();
	}

}
