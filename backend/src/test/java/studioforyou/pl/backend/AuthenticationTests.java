package studioforyou.pl.backend;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Firebase ID tokens are mocked at the {@link JwtDecoder}: each test token decodes to
 * a fixed Firebase UID. Everything after token verification runs for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, AuthenticationTests.ProtectedController.class })
@Transactional
class AuthenticationTests {

	private static final String ACTIVE_TOKEN = "active-token";

	private static final String PENDING_TOKEN = "pending-token";

	private static final String UNLINKED_TOKEN = "unlinked-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID activeAccountId;

	private UUID staffProfileId;

	private UUID ownProfileId;

	private UUID childProfileId;

	@BeforeEach
	void setUp() {
		activeAccountId = insertAccount("anna@example.com", "ACTIVE", "uid-active");
		addRole(activeAccountId, "CLIENT");
		addRole(activeAccountId, "TRAINER");
		staffProfileId = jdbc
			.sql("INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:id, 'Anna', 'Kowalska') RETURNING id")
			.param("id", activeAccountId)
			.query(UUID.class)
			.single();
		ownProfileId = insertCustomerProfile(activeAccountId, "ADULT", "Anna", "Kowalska");
		childProfileId = insertCustomerProfile(null, "CHILD", "Jan", "Kowalski");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", ownProfileId)
			.param("child", childProfileId)
			.update();

		UUID pendingAccountId = insertAccount("piotr@example.com", "PENDING_APPROVAL", "uid-pending");
		addRole(pendingAccountId, "CLIENT");

		givenToken(ACTIVE_TOKEN, "uid-active");
		givenToken(PENDING_TOKEN, "uid-pending");
		givenToken(UNLINKED_TOKEN, "uid-unlinked");
		given(jwtDecoder.decode("bad-token")).willThrow(new BadJwtException("bad signature"));
	}

	@Test
	void pingIsPublic() throws Exception {
		mvc.perform(get("/api/ping")).andExpect(status().isOk());
	}

	@Test
	void apiDocsAndHealthArePublic() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void meWithoutTokenIsUnauthorized() throws Exception {
		mvc.perform(get("/api/v1/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void meWithInvalidTokenIsUnauthorized() throws Exception {
		mvc.perform(get("/api/v1/me").header("Authorization", "Bearer bad-token"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void meReturnsTheActiveAccountWithItsProfiles() throws Exception {
		mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ACTIVE_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accountId").value(activeAccountId.toString()))
			.andExpect(jsonPath("$.email").value("anna@example.com"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.displayName").value("Anna Kowalska"))
			.andExpect(jsonPath("$.roles[0]").value("CLIENT"))
			.andExpect(jsonPath("$.roles[1]").value("TRAINER"))
			.andExpect(jsonPath("$.staffProfileId").value(staffProfileId.toString()))
			.andExpect(jsonPath("$.profiles[0].id").value(ownProfileId.toString()))
			.andExpect(jsonPath("$.profiles[0].isChild").value(false))
			.andExpect(jsonPath("$.profiles[1].id").value(childProfileId.toString()))
			.andExpect(jsonPath("$.profiles[1].displayName").value("Jan Kowalski"))
			.andExpect(jsonPath("$.profiles[1].isChild").value(true))
			.andExpect(jsonPath("$.activeProfileId").value(ownProfileId.toString()));
	}

	@Test
	void meReturnsAPendingAccountSoTheAppCanShowItsStatus() throws Exception {
		mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + PENDING_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
			.andExpect(jsonPath("$.displayName").value("piotr@example.com"))
			.andExpect(jsonPath("$.staffProfileId").isEmpty())
			.andExpect(jsonPath("$.profiles").isEmpty());
	}

	@Test
	void meForAFirebaseUserWithoutAnAccountIsForbidden() throws Exception {
		mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + UNLINKED_TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	@Test
	void otherEndpointsNeedAnActiveAccount() throws Exception {
		mvc.perform(get("/api/test/protected")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + UNLINKED_TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		mvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + PENDING_TOKEN))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/test/protected").header("Authorization", "Bearer " + ACTIVE_TOKEN))
			.andExpect(status().isOk());
	}

	private void givenToken(String token, String firebaseUid) {
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
	}

	private UUID insertAccount(String email, String status, String firebaseUid) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, :status) RETURNING id")
			.param("email", email)
			.param("status", status)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", firebaseUid)
			.update();
		return id;
	}

	private void addRole(UUID accountId, String roleCode) {
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", accountId)
			.param("code", roleCode)
			.update();
	}

	private UUID insertCustomerProfile(UUID accountId, String kind, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:accountId, :kind, :firstName, :lastName)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.query(UUID.class)
			.single();
	}

	/** Stands in for any endpoint other than the public ones and {@code /api/v1/me}. */
	@RestController
	static class ProtectedController {

		@GetMapping("/api/test/protected")
		String ok() {
			return "ok";
		}

	}

}
