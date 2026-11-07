package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role and permission checks on endpoints, and granting and revoking roles and permission
 * overrides through the API. Firebase ID tokens are mocked at the {@link JwtDecoder}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, AccessManagementTests.PermissionProtectedController.class })
@Transactional
class AccessManagementTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	private static final String CLIENT_TOKEN = "client-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID adminId;

	private UUID trainerId;

	private UUID clientId;

	@BeforeEach
	void setUp() {
		adminId = insertAccount("admin@example.com", "ACTIVE", "uid-admin", ADMIN_TOKEN);
		addRole(adminId, "ADMIN");
		trainerId = insertAccount("trainer@example.com", "ACTIVE", "uid-trainer", TRAINER_TOKEN);
		addRole(trainerId, "TRAINER");
		clientId = insertAccount("client@example.com", "ACTIVE", "uid-client", CLIENT_TOKEN);
		addRole(clientId, "CLIENT");
		// The studio's default permissions of trainers; migrations leave role_permission empty.
		jdbc.sql("""
				INSERT INTO role_permission (role_id, permission_id)
				SELECT r.id, p.id FROM role r, permission p
				WHERE r.code = 'TRAINER' AND p.code IN ('SCHEDULE_EDIT_OWN', 'AVAILABILITY_EDIT_OWN')
				""").update();
	}

	@Test
	void adminSeesTheRoleAndPermissionDictionaries() throws Exception {
		perform(get("/api/v1/roles"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].code").value(contains("CLIENT", "TRAINER", "ADMIN")))
			.andExpect(jsonPath("$.items[0].permissions").isEmpty())
			.andExpect(jsonPath("$.items[1].permissions").value(contains("SCHEDULE_EDIT_OWN", "AVAILABILITY_EDIT_OWN")))
			.andExpect(jsonPath("$.items[2].displayName").value("Administrator"));

		List<String> permissionCodes = jdbc.sql("SELECT code FROM permission").query(String.class).list();
		perform(get("/api/v1/roles"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.items[2].permissions").value(containsInAnyOrder(permissionCodes.toArray())));
		perform(get("/api/v1/permissions"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].code").value(containsInAnyOrder(permissionCodes.toArray())))
			.andExpect(jsonPath("$.items[0].category").value("IDENTITY"));
		perform(get("/api/v1/accounts/" + adminId + "/permissions"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.items").value(containsInAnyOrder(permissionCodes.toArray())));
	}

	@Test
	void anAdminHasEveryPermissionWhateverTheOverrides() throws Exception {
		jdbc.sql("""
				INSERT INTO account_permission_override (account_id, permission_id, effect, granted_by)
				SELECT :id, id, 'REVOKE', :id FROM permission WHERE code = 'SCHEDULE_MANAGE_ALL'
				""").param("id", adminId).update();
		List<String> permissionCodes = jdbc.sql("SELECT code FROM permission").query(String.class).list();

		perform(get("/api/v1/me"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.permissions").value(containsInAnyOrder(permissionCodes.toArray())));
		perform(get("/api/test/schedule"), ADMIN_TOKEN).andExpect(status().isOk());
	}

	@Test
	void permissionCodesDoNotLookLikeOtherAuthorities() {
		List<String> codes = jdbc.sql("SELECT code FROM permission").query(String.class).list();

		assertThat(codes).isNotEmpty().noneMatch((code) -> code.startsWith("ROLE_")).doesNotContain("ACCOUNT_ACTIVE");
	}

	@Test
	void onlyAdminsManageAccess() throws Exception {
		for (String token : List.of(TRAINER_TOKEN, CLIENT_TOKEN)) {
			perform(get("/api/v1/roles"), token).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
			perform(get("/api/v1/accounts/" + clientId + "/roles"), token).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
			perform(json(post("/api/v1/accounts/" + trainerId + "/roles"), "{\"code\":\"ADMIN\"}"), token)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		}
		assertThat(roleCodes(trainerId)).containsExactly("TRAINER");
	}

	@Test
	void aSuspendedAdminHasNoAdminAccess() throws Exception {
		jdbc.sql("UPDATE account SET status = 'SUSPENDED' WHERE id = :id").param("id", adminId).update();

		perform(get("/api/v1/roles"), ADMIN_TOKEN).andExpect(status().isForbidden());
	}

	@Test
	void adminGrantsARoleAndTheChangeIsAudited() throws Exception {
		perform(json(post("/api/v1/accounts/" + clientId + "/roles"), "{\"code\":\"TRAINER\"}"), ADMIN_TOKEN)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/v1/accounts/" + clientId + "/roles/TRAINER"))
			.andExpect(jsonPath("$.code").value("TRAINER"))
			.andExpect(jsonPath("$.displayName").value("Trener"))
			.andExpect(jsonPath("$.grantedBy").value(adminId.toString()))
			.andExpect(jsonPath("$.grantedAt").isNotEmpty());

		perform(get("/api/v1/accounts/" + clientId + "/roles"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].code").value(contains("CLIENT", "TRAINER")));
		perform(get("/api/v1/me"), CLIENT_TOKEN)
			.andExpect(jsonPath("$.roles").value(contains("CLIENT", "TRAINER")))
			.andExpect(jsonPath("$.permissions").value(contains("AVAILABILITY_EDIT_OWN", "SCHEDULE_EDIT_OWN")));

		Map<String, Object> audit = lastAuditEntry(clientId);
		assertThat(audit).containsEntry("actor_account_id", adminId)
			.containsEntry("module", "identity")
			.containsEntry("entity_type", "account")
			.containsEntry("action", "ROLE_GRANTED");
		assertThat(audit.get("changes").toString()).isEqualTo(
				"{\"roles\": {\"new\": [\"CLIENT\", \"TRAINER\"], \"old\": [\"CLIENT\"]}}");
	}

	@Test
	void grantingRejectsDuplicatesUnknownRolesAndUnknownAccounts() throws Exception {
		perform(json(post("/api/v1/accounts/" + clientId + "/roles"), "{\"code\":\"CLIENT\"}"), ADMIN_TOKEN)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROLE_ALREADY_GRANTED"));
		perform(json(post("/api/v1/accounts/" + clientId + "/roles"), "{\"code\":\"OWNER\"}"), ADMIN_TOKEN)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("UNKNOWN_ROLE"));
		perform(json(post("/api/v1/accounts/" + clientId + "/roles"), "{\"code\":\"\"}"), ADMIN_TOKEN)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("code"));
		perform(json(post("/api/v1/accounts/" + UUID.randomUUID() + "/roles"), "{\"code\":\"CLIENT\"}"), ADMIN_TOKEN)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		assertThat(auditCount()).isZero();
	}

	@Test
	void adminRevokesARoleAndTheChangeIsAudited() throws Exception {
		addRole(trainerId, "CLIENT");

		perform(delete("/api/v1/accounts/" + trainerId + "/roles/TRAINER"), ADMIN_TOKEN)
			.andExpect(status().isNoContent());

		assertThat(roleCodes(trainerId)).containsExactly("CLIENT");
		Map<String, Object> audit = lastAuditEntry(trainerId);
		assertThat(audit).containsEntry("action", "ROLE_REVOKED");
		assertThat(audit.get("changes").toString())
			.isEqualTo("{\"roles\": {\"new\": [\"CLIENT\"], \"old\": [\"CLIENT\", \"TRAINER\"]}}");

		perform(delete("/api/v1/accounts/" + trainerId + "/roles/TRAINER"), ADMIN_TOKEN)
			.andExpect(status().isNotFound());
		perform(delete("/api/v1/accounts/" + trainerId + "/roles/OWNER"), ADMIN_TOKEN)
			.andExpect(status().isNotFound());
	}

	@Test
	void theLastActiveAdminKeepsTheAdminRole() throws Exception {
		UUID suspendedAdmin = insertAccount("old-admin@example.com", "SUSPENDED", "uid-old-admin", "old-admin-token");
		addRole(suspendedAdmin, "ADMIN");

		perform(delete("/api/v1/accounts/" + adminId + "/roles/ADMIN"), ADMIN_TOKEN)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"));
		assertThat(roleCodes(adminId)).containsExactly("ADMIN");

		// Revoking from an inactive administrator leaves the number of active ones unchanged.
		perform(delete("/api/v1/accounts/" + suspendedAdmin + "/roles/ADMIN"), ADMIN_TOKEN)
			.andExpect(status().isNoContent());
	}

	@Test
	void anAdminCanStepDownWhenAnotherActiveAdminRemains() throws Exception {
		addRole(trainerId, "ADMIN");

		perform(delete("/api/v1/accounts/" + adminId + "/roles/ADMIN"), ADMIN_TOKEN)
			.andExpect(status().isNoContent());

		assertThat(roleCodes(adminId)).isEmpty();
		perform(get("/api/v1/roles"), ADMIN_TOKEN).andExpect(status().isForbidden());
		perform(get("/api/v1/roles"), TRAINER_TOKEN).andExpect(status().isOk());
	}

	@Test
	void aGrantOverrideGivesAPermissionNoRoleGives() throws Exception {
		perform(get("/api/test/schedule"), TRAINER_TOKEN).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

		perform(json(post("/api/v1/accounts/" + trainerId + "/permission-overrides"),
				"{\"permission\":\"SCHEDULE_MANAGE_ALL\",\"effect\":\"GRANT\"}"), ADMIN_TOKEN)
			.andExpect(status().isCreated())
			.andExpect(header().string("Location",
					"/api/v1/accounts/" + trainerId + "/permission-overrides/SCHEDULE_MANAGE_ALL"))
			.andExpect(jsonPath("$.permission").value("SCHEDULE_MANAGE_ALL"))
			.andExpect(jsonPath("$.effect").value("GRANT"))
			.andExpect(jsonPath("$.grantedBy").value(adminId.toString()));

		perform(get("/api/test/schedule"), TRAINER_TOKEN).andExpect(status().isOk());
		perform(get("/api/v1/accounts/" + trainerId + "/permission-overrides"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.items[0].permission").value("SCHEDULE_MANAGE_ALL"));
		Map<String, Object> audit = lastAuditEntry(trainerId);
		assertThat(audit).containsEntry("action", "PERMISSION_OVERRIDE_ADDED");
		assertThat(audit.get("changes").toString())
			.isEqualTo("{\"permissionOverride.SCHEDULE_MANAGE_ALL\": {\"new\": \"GRANT\", \"old\": null}}");

		perform(delete("/api/v1/accounts/" + trainerId + "/permission-overrides/SCHEDULE_MANAGE_ALL"), ADMIN_TOKEN)
			.andExpect(status().isNoContent());
		perform(get("/api/test/schedule"), TRAINER_TOKEN).andExpect(status().isForbidden());
		assertThat(lastAuditEntry(trainerId)).containsEntry("action", "PERMISSION_OVERRIDE_REMOVED");
	}

	@Test
	void aRevokeOverrideTakesAwayARoleDefault() throws Exception {
		perform(json(post("/api/v1/accounts/" + trainerId + "/permission-overrides"),
				"{\"permission\":\"AVAILABILITY_EDIT_OWN\",\"effect\":\"REVOKE\"}"), ADMIN_TOKEN)
			.andExpect(status().isCreated());

		perform(get("/api/v1/me"), TRAINER_TOKEN)
			.andExpect(jsonPath("$.permissions").value(contains("SCHEDULE_EDIT_OWN")));
		perform(get("/api/v1/accounts/" + trainerId + "/permissions"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.items").value(not(hasItem("AVAILABILITY_EDIT_OWN"))));
	}

	@Test
	void overridesRejectDuplicatesUnknownPermissionsAndMissingOnes() throws Exception {
		String body = "{\"permission\":\"PASS_ADJUST\",\"effect\":\"GRANT\"}";
		perform(json(post("/api/v1/accounts/" + trainerId + "/permission-overrides"), body), ADMIN_TOKEN)
			.andExpect(status().isCreated());
		perform(json(post("/api/v1/accounts/" + trainerId + "/permission-overrides"),
				"{\"permission\":\"PASS_ADJUST\",\"effect\":\"REVOKE\"}"), ADMIN_TOKEN)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERMISSION_OVERRIDE_EXISTS"));
		perform(json(post("/api/v1/accounts/" + trainerId + "/permission-overrides"),
				"{\"permission\":\"EVERYTHING\",\"effect\":\"GRANT\"}"), ADMIN_TOKEN)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"));
		perform(json(post("/api/v1/accounts/" + trainerId + "/permission-overrides"),
				"{\"permission\":\"PASS_ADJUST\"}"), ADMIN_TOKEN)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		perform(delete("/api/v1/accounts/" + trainerId + "/permission-overrides/ATTENDANCE_CORRECT"), ADMIN_TOKEN)
			.andExpect(status().isNotFound());
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String token) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + token));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private UUID insertAccount(String email, String status, String firebaseUid, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, :status) RETURNING id")
			.param("email", email)
			.param("status", status)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", firebaseUid)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
		return id;
	}

	private void addRole(UUID accountId, String roleCode) {
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", accountId)
			.param("code", roleCode)
			.update();
	}

	private List<String> roleCodes(UUID accountId) {
		return jdbc.sql("""
				SELECT r.code FROM account_role ar JOIN role r ON r.id = ar.role_id
				WHERE ar.account_id = :id ORDER BY r.code
				""").param("id", accountId).query(String.class).list();
	}

	private Map<String, Object> lastAuditEntry(UUID accountId) {
		return jdbc.sql("""
				SELECT actor_account_id, module, entity_type, action, changes::text AS changes
				FROM audit_log WHERE entity_id = :id ORDER BY id DESC LIMIT 1
				""").param("id", accountId).query().singleRow();
	}

	private long auditCount() {
		return jdbc.sql("SELECT count(*) FROM audit_log").query(Long.class).single();
	}

	/** Stands in for an endpoint that needs a permission rather than a role. */
	@RestController
	static class PermissionProtectedController {

		@GetMapping("/api/test/schedule")
		@PreAuthorize("hasAuthority('SCHEDULE_MANAGE_ALL')")
		String manageSchedule() {
			return "ok";
		}

	}

}
