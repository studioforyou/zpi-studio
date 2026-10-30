package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
 * committed save.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerProfileTests {

	private static final String OWN = "/api/v1/me/customer-profile";

	private static final String CLIENT = "client-token";

	private static final String OTHER_CLIENT = "other-client-token";

	private static final String TRAINER = "trainer-token";

	private static final String NO_PROFILE = "no-profile-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID clientAccountId;

	private UUID ownProfileId;

	private UUID childProfileId;

	private UUID otherProfileId;

	private UUID otherChildProfileId;

	@BeforeEach
	void setUp() {
		clientAccountId = addAccount("client", "ACTIVE", CLIENT, "CLIENT");
		ownProfileId = addProfile(clientAccountId, "ADULT", "Anna", "Kowalska", "+48 600 100 200");
		childProfileId = addProfile(null, "CHILD", "Jan", "Kowalski", null);
		addGuardianship(ownProfileId, childProfileId);
		UUID otherAccountId = addAccount("other", "ACTIVE", OTHER_CLIENT, "CLIENT");
		otherProfileId = addProfile(otherAccountId, "ADULT", "Piotr", "Nowak", "987654321");
		otherChildProfileId = addProfile(null, "CHILD", "Ola", "Nowak", null);
		addGuardianship(otherProfileId, otherChildProfileId);
		addAccount("trainer", "ACTIVE", TRAINER, "TRAINER");
		addAccount("missing", "ACTIVE", NO_PROFILE, "CLIENT");
		given(jwtDecoder.decode("bad-token")).willThrow(new BadJwtException("Invalid signature"));
	}

	@AfterEach
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE 'customer-profile-%@example.com'";
		String profiles = "SELECT id FROM customer_profile WHERE account_id IN (" + accounts + ")";
		var children = jdbc.sql("SELECT child_id FROM guardianship WHERE guardian_id IN (" + profiles + ")")
			.query(UUID.class)
			.list();
		jdbc.sql("DELETE FROM guardianship WHERE guardian_id IN (" + profiles + ")").update();
		if (!children.isEmpty()) {
			jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)").param("ids", children).update();
		}
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE 'customer-profile-%@example.com'").update();
	}

	@Test
	void clientReadsTheirOwnProfile() throws Exception {
		perform(CLIENT, get(OWN), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(ownProfileId.toString()))
			.andExpect(jsonPath("$.kind").value("ADULT"))
			.andExpect(jsonPath("$.firstName").value("Anna"))
			.andExpect(jsonPath("$.lastName").value("Kowalska"))
			.andExpect(jsonPath("$.phone").value("+48 600 100 200"))
			.andExpect(jsonPath("$.birthDate").isEmpty())
			.andExpect(jsonPath("$.emergencyContactName").isEmpty());
		perform(CLIENT, get(byId(ownProfileId)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(ownProfileId.toString()));
	}

	@Test
	void saveIsCommittedTrimmedAndUpdatesTimestampAndName() throws Exception {
		jdbc.sql("UPDATE customer_profile SET updated_at = '2000-01-01T00:00:00Z' WHERE id = :id")
			.param("id", ownProfileId)
			.update();
		perform(CLIENT, patch(OWN), """
				{ "firstName": "  Alicja ", "lastName": "\\tNowak\\n", "birthDate": "1990-05-17",
				  "phone": " 600-100-200 ", "emergencyContactName": " Marek ", "emergencyPhone": "+48 (12) 345 67 89" }
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Alicja"))
			.andExpect(jsonPath("$.lastName").value("Nowak"))
			.andExpect(jsonPath("$.birthDate").value("1990-05-17"))
			.andExpect(jsonPath("$.phone").value("600-100-200"))
			.andExpect(jsonPath("$.emergencyContactName").value("Marek"))
			.andExpect(jsonPath("$.emergencyPhone").value("+48 (12) 345 67 89"));

		perform(CLIENT, get(OWN), null)
			.andExpect(jsonPath("$.firstName").value("Alicja"))
			.andExpect(jsonPath("$.emergencyContactName").value("Marek"));
		assertThat(jdbc.sql("SELECT updated_at FROM customer_profile WHERE id = :id")
			.param("id", ownProfileId).query(OffsetDateTime.class).single())
			.isAfter(OffsetDateTime.parse("2000-01-01T00:00:00Z"));
		perform(CLIENT, get("/api/v1/me"), null)
			.andExpect(jsonPath("$.displayName").value("Alicja Nowak"));
	}

	@Test
	void omittedFieldsAndEmptyPatchKeepTheCurrentValues() throws Exception {
		perform(CLIENT, patch(OWN), "{\"birthDate\":\"1990-05-17\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Anna"))
			.andExpect(jsonPath("$.phone").value("+48 600 100 200"))
			.andExpect(jsonPath("$.birthDate").value("1990-05-17"));
		perform(CLIENT, patch(OWN), "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lastName").value("Kowalska"))
			.andExpect(jsonPath("$.birthDate").value("1990-05-17"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "null", "\"\"", "\" \\t\\n \"", "\"\\u00a0\\u0085\\ufeff\"" })
	void optionalValuesCanBeCleared(String value) throws Exception {
		perform(CLIENT, patch(OWN), """
				{ "emergencyContactName": "Marek", "emergencyPhone": "601200300", "birthDate": "1990-05-17" }
				""").andExpect(status().isOk());
		perform(CLIENT, patch(OWN), """
				{ "phone": %1$s, "emergencyContactName": %1$s, "emergencyPhone": %1$s, "birthDate": null }
				""".formatted(value))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phone").isEmpty())
			.andExpect(jsonPath("$.emergencyContactName").isEmpty())
			.andExpect(jsonPath("$.emergencyPhone").isEmpty())
			.andExpect(jsonPath("$.birthDate").isEmpty());
		assertThat(jdbc.sql("""
				SELECT count(*) FROM customer_profile
				WHERE id = :id AND phone IS NULL AND emergency_contact_name IS NULL AND emergency_phone IS NULL
				  AND birth_date IS NULL
				""").param("id", ownProfileId).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void guardianReadsAndEditsTheirChild() throws Exception {
		perform(CLIENT, patch(byId(childProfileId)), "{\"firstName\":\"Jasiek\",\"birthDate\":\"2016-02-01\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(childProfileId.toString()))
			.andExpect(jsonPath("$.kind").value("CHILD"))
			.andExpect(jsonPath("$.firstName").value("Jasiek"))
			.andExpect(jsonPath("$.lastName").value("Kowalski"));
		perform(CLIENT, get(byId(childProfileId)), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.birthDate").value("2016-02-01"));
		perform(CLIENT, get(OWN), null).andExpect(jsonPath("$.firstName").value("Anna"));
	}

	@Test
	void endedGuardianshipNoLongerAllowsEditing() throws Exception {
		jdbc.sql("UPDATE guardianship SET ended_at = now() WHERE child_id = :child")
			.param("child", childProfileId)
			.update();
		perform(CLIENT, get(byId(childProfileId)), null).andExpect(status().isNotFound());
	}

	@Test
	void otherProfilesAreNotFoundAndUnchanged() throws Exception {
		for (UUID profileId : new UUID[] { otherProfileId, otherChildProfileId, UUID.randomUUID() }) {
			perform(CLIENT, get(byId(profileId)), null)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
			perform(CLIENT, patch(byId(profileId)), "{\"firstName\":\"Changed\"}")
				.andExpect(status().isNotFound());
		}
		perform(OTHER_CLIENT, get(OWN), null).andExpect(jsonPath("$.firstName").value("Piotr"));
		perform(OTHER_CLIENT, get(byId(otherChildProfileId)), null).andExpect(jsonPath("$.firstName").value("Ola"));
	}

	@Test
	void suppliedIdentifiersCannotTargetAnotherProfile() throws Exception {
		perform(CLIENT, patch(OWN), """
				{ "id": "%s", "kind": "CHILD", "firstName": "Changed" }
				""".formatted(otherProfileId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(ownProfileId.toString()))
			.andExpect(jsonPath("$.kind").value("ADULT"));
		perform(OTHER_CLIENT, get(OWN), null).andExpect(jsonPath("$.firstName").value("Piotr"));
	}

	@Test
	void namesMayUseLettersOfAnyAlphabetWithSeparators() throws Exception {
		perform(CLIENT, patch(OWN), """
				{ "firstName": "Anna-Maria", "lastName": "O'Neil Żółkiewska", "emergencyContactName": "J. Ó Briain",
				  "emergencyPhone": "601200300" }
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lastName").value("O'Neil Żółkiewska"))
			.andExpect(jsonPath("$.emergencyContactName").value("J. Ó Briain"));
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void invalidFieldsUseApiProblemAndDoNotSaveAnyChanges(String body, String field, String code) throws Exception {
		perform(CLIENT, patch(OWN), body)
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value(field))
			.andExpect(jsonPath("$.errors[0].code").value(code));
		perform(CLIENT, get(OWN), null)
			.andExpect(jsonPath("$.firstName").value("Anna"))
			.andExpect(jsonPath("$.phone").value("+48 600 100 200"))
			.andExpect(jsonPath("$.emergencyContactName").isEmpty());
	}

	static Stream<Arguments> invalidFields() {
		String tomorrow = LocalDate.now().plusDays(1).toString();
		return Stream.of(
				Arguments.of("{\"firstName\":null}", "firstName", "REQUIRED"),
				Arguments.of("{\"lastName\":\" \\t \"}", "lastName", "REQUIRED"),
				Arguments.of("{\"firstName\":\"" + "a".repeat(81) + "\"}", "firstName", "TOO_LONG"),
				Arguments.of("{\"phone\":\"" + "1".repeat(21) + "\"}", "phone", "TOO_LONG"),
				Arguments.of("{\"phone\":\"abc123\"}", "phone", "INVALID_FORMAT"),
				Arguments.of("{\"firstName\":\"Anna2\"}", "firstName", "INVALID_FORMAT"),
				Arguments.of("{\"lastName\":\"12345\"}", "lastName", "INVALID_FORMAT"),
				Arguments.of("{\"emergencyContactName\":\"M4rek\",\"emergencyPhone\":\"601200300\"}",
						"emergencyContactName", "INVALID_FORMAT"),
				Arguments.of("{\"emergencyContactName\":\"Marek\",\"emergencyPhone\":\"tel\"}", "emergencyPhone",
						"INVALID_FORMAT"),
				Arguments.of("{\"birthDate\":\"" + tomorrow + "\"}", "birthDate", "INVALID_VALUE"),
				Arguments.of("{\"emergencyContactName\":\"" + "m".repeat(121) + "\",\"emergencyPhone\":\"601200300\"}",
						"emergencyContactName", "TOO_LONG"),
				Arguments.of("{\"emergencyContactName\":\"Marek\"}", "emergencyContactComplete", "INVALID_VALUE"),
				Arguments.of("{\"emergencyPhone\":\"601200300\"}", "emergencyContactComplete", "INVALID_VALUE"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{", "null", "{\"birthDate\":\"17.05.1990\"}", "{\"firstName\":{}}" })
	void malformedBodiesUseApiProblem(String body) throws Exception {
		perform(CLIENT, patch(OWN), body)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void missingOwnProfileIsNotFound() throws Exception {
		perform(NO_PROFILE, get(OWN), null).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(NO_PROFILE, patch(OWN), "{\"firstName\":\"Changed\"}").andExpect(status().isNotFound());
	}

	@Test
	void missingOrInvalidTokenIsUnauthorized() throws Exception {
		for (String token : new String[] { null, "bad-token" }) {
			perform(token, get(OWN), null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
			perform(token, patch(byId(ownProfileId)), "{\"firstName\":\"Changed\"}")
				.andExpect(status().isUnauthorized());
		}
	}

	@Test
	void onlyAClientCanReadOrWrite() throws Exception {
		perform(TRAINER, get(OWN), null).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		perform(TRAINER, patch(byId(ownProfileId)), "{\"firstName\":\"Changed\"}")
			.andExpect(status().isForbidden());
	}

	@ParameterizedTest
	@ValueSource(strings = { "PENDING_APPROVAL", "SUSPENDED" })
	void inactiveClientCannotReadOrWrite(String accountStatus) throws Exception {
		jdbc.sql("UPDATE account SET status = :status WHERE id = :id")
			.param("status", accountStatus).param("id", clientAccountId).update();
		perform(CLIENT, get(OWN), null).andExpect(status().isForbidden());
		perform(CLIENT, patch(byId(childProfileId)), "{\"firstName\":\"Changed\"}")
			.andExpect(status().isForbidden());
		assertThat(jdbc.sql("SELECT first_name FROM customer_profile WHERE id = :id")
			.param("id", childProfileId).query(String.class).single()).isEqualTo("Jan");
	}

	private static String byId(UUID profileId) {
		return "/api/v1/me/customer-profiles/" + profileId;
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
			.param("email", "customer-profile-" + name + "@example.com")
			.param("status", accountStatus).query(UUID.class).single();
		String uid = "uid-customer-profile-" + name;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
				.param("id", id).param("role", role).update();
		}
		given(jwtDecoder.decode(token))
			.willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID addProfile(UUID accountId, String kind, String firstName, String lastName, String phone) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name, phone)
				VALUES (:accountId, :kind, :firstName, :lastName, :phone) RETURNING id
				""")
			.param("accountId", accountId).param("kind", kind).param("firstName", firstName)
			.param("lastName", lastName).param("phone", phone).query(UUID.class).single();
	}

	private void addGuardianship(UUID guardianId, UUID childId) {
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", guardianId).param("child", childId).update();
	}

}
