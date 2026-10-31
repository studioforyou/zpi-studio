package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The JWT decoder is mocked; account lookup, authorization, validation and PostgreSQL writes
 * run for real. Tests have no enclosing transaction, so a subsequent read verifies a
 * committed save and failures cannot be hidden by a test rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StaffProfileTests {

	private static final String PATH = "/api/v1/me/staff-profile";

	private static final String TRAINER = "trainer-token";

	private static final String OTHER_TRAINER = "other-trainer-token";

	private static final String CLIENT = "client-token";

	private static final String ADMIN = "admin-token";

	private static final String NO_PROFILE = "no-profile-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID trainerAccountId;

	private UUID trainerProfileId;

	private UUID otherProfileId;

	private UUID ownCustomerProfileId;

	@BeforeEach
	void setUp() {
		trainerAccountId = addAccount("trainer", "ACTIVE", TRAINER, "CLIENT", "TRAINER");
		trainerProfileId = addStaffProfile(trainerAccountId, "Anna", "Kowalska", "+48 123 456 789");
		UUID otherAccountId = addAccount("other", "ACTIVE", OTHER_TRAINER, "TRAINER");
		otherProfileId = addStaffProfile(otherAccountId, "Piotr", "Nowak", "987654321");
		UUID clientId = addAccount("client", "ACTIVE", CLIENT, "CLIENT");
		addStaffProfile(clientId, "Client", "Profile", null);
		addAccount("admin", "ACTIVE", ADMIN, "ADMIN");
		addAccount("missing", "ACTIVE", NO_PROFILE, "TRAINER");
		ownCustomerProfileId = jdbc.sql("""
				INSERT INTO customer_profile (account_id, first_name, last_name, phone)
				VALUES (:accountId, 'Customer', 'Name', '111222333') RETURNING id
				""")
			.param("accountId", trainerAccountId)
			.query(UUID.class)
			.single();
		givenToken("unlinked-token", "uid-staff-profile-unlinked");
		given(jwtDecoder.decode("bad-token")).willThrow(new BadJwtException("Invalid signature"));
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE 'staff-profile-%@example.com'";
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE 'staff-profile-%@example.com'").update();
	}

	@Test
	void trainerReadsOnlyTheirOwnProfile() throws Exception {
		perform(TRAINER, get(PATH), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(trainerProfileId.toString()))
			.andExpect(jsonPath("$.firstName").value("Anna"))
			.andExpect(jsonPath("$.lastName").value("Kowalska"))
			.andExpect(jsonPath("$.phone").value("+48 123 456 789"));
		perform(OTHER_TRAINER, get(PATH), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(otherProfileId.toString()))
			.andExpect(jsonPath("$.firstName").value("Piotr"));
	}

	@Test
	void saveIsCommittedTrimmedAndUpdatesTimestampWithoutChangingTheCustomerProfile() throws Exception {
		jdbc.sql("UPDATE staff_profile SET updated_at = '2000-01-01T00:00:00Z' WHERE id = :id")
			.param("id", trainerProfileId)
			.update();
		perform(TRAINER, patch(PATH), """
				{ "firstName": "  Alicja  ", "lastName": "\\tNowak\\n", "phone": "  +48 555 444 333  " }
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Alicja"))
			.andExpect(jsonPath("$.lastName").value("Nowak"))
			.andExpect(jsonPath("$.phone").value("+48 555 444 333"));

		perform(TRAINER, get(PATH), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Alicja"))
			.andExpect(jsonPath("$.lastName").value("Nowak"));
		assertThat(jdbc.sql("SELECT first_name || ' ' || last_name FROM staff_profile WHERE id = :id")
			.param("id", trainerProfileId).query(String.class).single()).isEqualTo("Alicja Nowak");
		assertThat(jdbc.sql("SELECT updated_at FROM staff_profile WHERE id = :id")
			.param("id", trainerProfileId).query(OffsetDateTime.class).single())
			.isAfter(OffsetDateTime.parse("2000-01-01T00:00:00Z"));
		assertThat(jdbc.sql("SELECT first_name || ' ' || last_name || ' ' || phone FROM customer_profile WHERE id = :id")
			.param("id", ownCustomerProfileId).query(String.class).single()).isEqualTo("Customer Name 111222333");
		perform(TRAINER, get("/api/v1/me"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Customer Name"))
			.andExpect(jsonPath("$.profiles[0].displayName").value("Customer Name"))
			.andExpect(jsonPath("$.activeProfileId").value(ownCustomerProfileId.toString()));
	}

	@Test
	void trainerOnlyAccountGetsItsUpdatedNameInMe() throws Exception {
		perform(OTHER_TRAINER, patch(PATH), "{\"firstName\":\"Adam\"}").andExpect(status().isOk());
		perform(OTHER_TRAINER, get("/api/v1/me"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Adam Nowak"));
	}

	@Test
	void omittedFieldsAndEmptyPatchKeepTheCurrentValues() throws Exception {
		perform(TRAINER, patch(PATH), "{\"firstName\":\"Maria\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Maria"))
			.andExpect(jsonPath("$.lastName").value("Kowalska"))
			.andExpect(jsonPath("$.phone").value("+48 123 456 789"));
		perform(TRAINER, patch(PATH), "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Maria"))
			.andExpect(jsonPath("$.lastName").value("Kowalska"))
			.andExpect(jsonPath("$.phone").value("+48 123 456 789"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "null", "\"\"", "\" \\t\\n \"", "\"\\u00a0\\u0085\\ufeff\"" })
	void phoneCanBeCleared(String value) throws Exception {
		perform(TRAINER, patch(PATH), "{\"phone\":" + value + "}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phone").isEmpty());
		perform(TRAINER, get(PATH), null).andExpect(jsonPath("$.phone").isEmpty());
		assertThat(jdbc.sql("SELECT count(*) FROM staff_profile WHERE id = :id AND phone IS NULL")
			.param("id", trainerProfileId).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void limitsApplyAfterUnicodeWhitespaceTrimming() throws Exception {
		String firstName = "a".repeat(80);
		String lastName = "b".repeat(80);
		String phone = "1".repeat(20);
		perform(TRAINER, patch(PATH), """
				{ "firstName": "\\u00a0%s\\ufeff", "lastName": " %s ", "phone": " %s " }
				""".formatted(firstName, lastName, phone))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value(firstName))
			.andExpect(jsonPath("$.lastName").value(lastName))
			.andExpect(jsonPath("$.phone").value(phone));
	}

	@Test
	void trimmingPreservesWhitespaceInsideValues() throws Exception {
		perform(TRAINER, patch(PATH), """
				{ "firstName": "\\u0085 Anna  Maria\\u00a0", "phone": " +48  123 " }
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Anna  Maria"))
			.andExpect(jsonPath("$.phone").value("+48  123"));
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void invalidFieldsUseApiProblemAndDoNotSaveAnyChanges(String body, String field, String code) throws Exception {
		perform(TRAINER, patch(PATH), body)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.instance").value(PATH))
			.andExpect(jsonPath("$.errors[0].field").value(field))
			.andExpect(jsonPath("$.errors[0].code").value(code));
		perform(TRAINER, get(PATH), null)
			.andExpect(jsonPath("$.firstName").value("Anna"))
			.andExpect(jsonPath("$.lastName").value("Kowalska"))
			.andExpect(jsonPath("$.phone").value("+48 123 456 789"));
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of(
				Arguments.of("{\"firstName\":null}", "firstName", "REQUIRED"),
				Arguments.of("{\"lastName\":null}", "lastName", "REQUIRED"),
				Arguments.of("{\"firstName\":\" \\t \",\"phone\":null}", "firstName", "REQUIRED"),
				Arguments.of("{\"lastName\":\"\\u00a0\\ufeff\"}", "lastName", "REQUIRED"),
				Arguments.of("{\"firstName\":\"" + "a".repeat(81) + "\"}", "firstName", "TOO_LONG"),
				Arguments.of("{\"lastName\":\"" + "b".repeat(81) + "\"}", "lastName", "TOO_LONG"),
				Arguments.of("{\"firstName\":\"Changed\",\"phone\":\"" + "1".repeat(21) + "\"}", "phone", "TOO_LONG"),
				Arguments.of("{\"firstName\":\"Anna2\"}", "firstName", "INVALID_FORMAT"),
				Arguments.of("{\"lastName\":\"12345\"}", "lastName", "INVALID_FORMAT"),
				Arguments.of("{\"lastName\":\"Nowak!\"}", "lastName", "INVALID_FORMAT"),
				Arguments.of("{\"phone\":\"abcdef\"}", "phone", "INVALID_FORMAT"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{", "null", "{\"firstName\":{}}" })
	void malformedBodiesUseApiProblem(String body) throws Exception {
		perform(TRAINER, patch(PATH), body)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void suppliedIdentifiersCannotTargetAnotherProfile() throws Exception {
		perform(TRAINER, patch(PATH + "?accountId=" + UUID.randomUUID()), """
				{ "id": "%s", "accountId": "%s", "firstName": "Changed" }
				""".formatted(otherProfileId, UUID.randomUUID()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(trainerProfileId.toString()));
		perform(OTHER_TRAINER, get(PATH), null)
			.andExpect(jsonPath("$.firstName").value("Piotr"))
			.andExpect(jsonPath("$.lastName").value("Nowak"))
			.andExpect(jsonPath("$.phone").value("987654321"));
	}

	@Test
	void missingOrInvalidTokenCannotReadOrWrite() throws Exception {
		for (String token : new String[] { null, "bad-token" }) {
			perform(token, get(PATH), null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
			perform(token, patch(PATH), "{\"firstName\":\"Changed\"}")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { CLIENT, ADMIN, "unlinked-token" })
	void onlyATrainerCanReadOrWrite(String token) throws Exception {
		perform(token, get(PATH), null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		perform(token, patch(PATH), "{\"firstName\":\"Changed\"}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "INVITED", "PENDING_APPROVAL", "REJECTED", "SUSPENDED", "CLOSED" })
	void inactiveTrainerCannotReadOrWrite(String accountStatus) throws Exception {
		jdbc.sql("UPDATE account SET status = :status WHERE id = :id")
			.param("status", accountStatus).param("id", trainerAccountId).update();
		perform(TRAINER, get(PATH), null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		perform(TRAINER, patch(PATH), "{\"firstName\":\"Changed\"}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		assertThat(jdbc.sql("SELECT first_name FROM staff_profile WHERE id = :id")
			.param("id", trainerProfileId).query(String.class).single()).isEqualTo("Anna");
	}

	@Test
	void missingStaffProfileIsNotFoundForBothOperations() throws Exception {
		perform(NO_PROFILE, get(PATH), null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(NO_PROFILE, patch(PATH), "{\"firstName\":\"Changed\"}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String body) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private UUID addAccount(String name, String accountStatus, String token, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, :status) RETURNING id")
			.param("email", "staff-profile-" + name + "@example.com")
			.param("status", accountStatus).query(UUID.class).single();
		String uid = "uid-staff-profile-" + name;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
				.param("id", id).param("role", role).update();
		}
		givenToken(token, uid);
		return id;
	}

	private void givenToken(String token, String uid) {
		given(jwtDecoder.decode(token))
			.willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
	}

	private UUID addStaffProfile(UUID accountId, String firstName, String lastName, String phone) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name, phone)
				VALUES (:accountId, :firstName, :lastName, :phone) RETURNING id
				""")
			.param("accountId", accountId).param("firstName", firstName).param("lastName", lastName)
			.param("phone", phone).query(UUID.class).single();
	}

}
