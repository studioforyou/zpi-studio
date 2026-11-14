package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import studioforyou.pl.backend.communication.mail.EmailMessage;
import studioforyou.pl.backend.communication.mail.EmailSender;
import studioforyou.pl.backend.identity.firebase.FirebaseUsers;
import studioforyou.pl.backend.identity.firebase.FirebaseUsers.FirebaseUser;

/**
 * Administrators read who created, activated, suspended and changed the access of accounts, and when.
 * Not transactional, so every request saves its changes at its own time, as in production. E-mail,
 * Firebase and ID tokens are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountHistoryTests {

	private static final String DOMAIN = "@history.test";

	private static final String ADMIN_TOKEN = "history-admin-token";

	private static final String TRAINER_TOKEN = "history-trainer-token";

	private static final String CLIENT_TOKEN = "history-client-token";

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private FirebaseUsers firebaseUsers;

	@MockitoBean
	private EmailSender emailSender;

	private UUID admin;

	private UUID trainer;

	private UUID client;

	@BeforeEach
	void setUp() {
		admin = addAccount("beata" + DOMAIN, "ADMIN", "uid-history-admin", ADMIN_TOKEN);
		jdbc.sql("INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:id, 'Beata', 'Lis')")
			.param("id", admin)
			.update();
		trainer = addAccount("marek" + DOMAIN, "TRAINER", "uid-history-trainer", TRAINER_TOKEN);
		jdbc.sql("INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:id, 'Marek', 'Nowak')")
			.param("id", trainer)
			.update();
		client = addAccount("kasia" + DOMAIN, "CLIENT", "uid-history-client", CLIENT_TOKEN);
		jdbc.sql("""
				INSERT INTO customer_profile (account_id, first_name, last_name)
				VALUES (:id, 'Katarzyna', 'Zielińska')
				""").param("id", client).update();
	}

	@AfterEach
	void tearDown() {
		String accounts = "(SELECT id FROM account WHERE email ILIKE '%" + DOMAIN + "')";
		jdbc.sql("DELETE FROM audit_log WHERE entity_id IN " + accounts + " OR actor_account_id IN " + accounts)
			.update();
		for (String table : new String[] { "staff_profile", "customer_profile", "account_invitation",
				"account_status_change", "account_permission_override", "account_role", "identity_link" }) {
			jdbc.sql("DELETE FROM " + table + " WHERE account_id IN " + accounts).update();
		}
		jdbc.sql("DELETE FROM account WHERE id IN " + accounts).update();
	}

	@Test
	void showsWhoCreatedActivatedAndChangedAnAccountNewestFirst() throws Exception {
		perform(json(post("/api/v1/invitations"), """
				{"email": "julia%s", "firstName": "Julia", "lastName": "Kamińska", "role": "CLIENT"}
				""".formatted(DOMAIN)), ADMIN_TOKEN).andExpect(status().isCreated());
		UUID julia = jdbc.sql("SELECT id FROM account WHERE email = :email")
			.param("email", "julia" + DOMAIN)
			.query(UUID.class)
			.single();
		given(firebaseUsers.createOrUpdate(anyString(), anyString())).willReturn(new FirebaseUser("uid-julia", true));
		mvc.perform(json(post("/api/v1/invitations/" + invitationToken() + "/accept"), "{\"password\":\"Secret123!\"}"))
			.andExpect(status().isOk());
		String account = "/api/v1/accounts/" + julia;
		perform(json(post(account + "/roles"), "{\"code\":\"TRAINER\"}"), ADMIN_TOKEN).andExpect(status().isCreated());
		perform(json(post(account + "/permission-overrides"), "{\"permission\":\"PASS_ADJUST\",\"effect\":\"GRANT\"}"),
				ADMIN_TOKEN)
			.andExpect(status().isCreated());
		perform(delete(account + "/permission-overrides/PASS_ADJUST"), ADMIN_TOKEN).andExpect(status().isNoContent());
		perform(delete(account + "/roles/TRAINER"), ADMIN_TOKEN).andExpect(status().isNoContent());
		perform(json(post(account + "/suspend"), "{\"reason\":\"Zaległa płatność\"}"), ADMIN_TOKEN)
			.andExpect(status().isOk());
		perform(post(account + "/reactivate"), ADMIN_TOKEN).andExpect(status().isOk());

		perform(get("/api/v1/account-history").param("accountId", julia.toString()), ADMIN_TOKEN)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(9))
			.andExpect(jsonPath("$.items[*].type").value(contains("STATUS_CHANGED", "STATUS_CHANGED", "ROLE_REVOKED",
					"PERMISSION_OVERRIDE_REMOVED", "PERMISSION_OVERRIDE_ADDED", "ROLE_GRANTED", "STATUS_CHANGED",
					"ROLE_GRANTED", "STATUS_CHANGED")))
			.andExpect(jsonPath("$.items[*].account.displayName").value(everyItem(is("Julia Kamińska"))))
			// Reactivated without a reason: the backend's default reason is not shown.
			.andExpect(jsonPath("$.items[0].fromStatus").value("SUSPENDED"))
			.andExpect(jsonPath("$.items[0].toStatus").value("ACTIVE"))
			.andExpect(jsonPath("$.items[0].actor.displayName").value("Beata Lis"))
			.andExpect(jsonPath("$.items[0].reason").doesNotExist())
			.andExpect(jsonPath("$.items[1].toStatus").value("SUSPENDED"))
			.andExpect(jsonPath("$.items[1].reason").value("Zaległa płatność"))
			.andExpect(jsonPath("$.items[2].role").value("TRAINER"))
			.andExpect(jsonPath("$.items[3].permission").value("PASS_ADJUST"))
			.andExpect(jsonPath("$.items[3].effect").value("GRANT"))
			.andExpect(jsonPath("$.items[4].permission").value("PASS_ADJUST"))
			.andExpect(jsonPath("$.items[4].effect").value("GRANT"))
			.andExpect(jsonPath("$.items[5].role").value("TRAINER"))
			.andExpect(jsonPath("$.items[5].actor.accountId").value(admin.toString()))
			// Julia activated the account herself by accepting the invitation.
			.andExpect(jsonPath("$.items[6].fromStatus").value("INVITED"))
			.andExpect(jsonPath("$.items[6].toStatus").value("ACTIVE"))
			.andExpect(jsonPath("$.items[6].actor.accountId").value(julia.toString()))
			.andExpect(jsonPath("$.items[6].actor.displayName").value("Julia Kamińska"))
			.andExpect(jsonPath("$.items[6].reason").doesNotExist())
			// The invitation created the account and gave it its role in the same transaction.
			.andExpect(jsonPath("$.items[7].role").value("CLIENT"))
			.andExpect(jsonPath("$.items[7].actor.displayName").value("Beata Lis"))
			.andExpect(jsonPath("$.items[8].fromStatus").doesNotExist())
			.andExpect(jsonPath("$.items[8].toStatus").value("INVITED"))
			.andExpect(jsonPath("$.items[8].actor.displayName").value("Beata Lis"));

		perform(get("/api/v1/account-history").param("accountId", julia.toString()).param("size", "4")
			.param("page", "2"), ADMIN_TOKEN)
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.items[*].toStatus").value(contains("INVITED")));
	}

	@Test
	void filtersByAccountAuthorTypeNameAndStudioDate() throws Exception {
		// Katarzyna registers on 1 October; Beata approves her at 23:30 on 2 October in Warsaw.
		addStatusChange(client, null, "PENDING_APPROVAL", null, null, "2026-10-01T10:00:00Z");
		addStatusChange(client, "PENDING_APPROVAL", "ACTIVE", admin, "Zweryfikowana na recepcji",
				"2026-10-02T21:30:00Z");
		// 00:30 on 3 October in Warsaw. An older entry that names the one role.
		addAudit("identity", "account", trainer, "ROLE_GRANTED", "{\"role\": {\"old\": null, \"new\": \"TRAINER\"}}",
				"2026-10-02T22:30:00Z");
		// Neither is a change of access: a status already in the status history and another module's entry.
		addAudit("identity", "account", client, "ACCOUNT_APPROVED",
				"{\"status\": {\"old\": \"PENDING_APPROVAL\", \"new\": \"ACTIVE\"}}", "2026-10-02T21:30:00Z");
		addAudit("scheduling", "schedule_event", trainer, "EVENT_CANCELLED", null, "2026-10-04T10:00:00Z");

		history(ADMIN_TOKEN, "query", DOMAIN).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(3))
			.andExpect(jsonPath("$.items[0].type").value("ROLE_GRANTED"))
			.andExpect(jsonPath("$.items[0].role").value("TRAINER"))
			.andExpect(jsonPath("$.items[0].account.displayName").value("Marek Nowak"));
		history(ADMIN_TOKEN, "accountId", client.toString(), "type", "STATUS_CHANGED")
			.andExpect(jsonPath("$.items[*].toStatus").value(contains("ACTIVE", "PENDING_APPROVAL")))
			.andExpect(jsonPath("$.items[0].actor.displayName").value("Beata Lis"))
			.andExpect(jsonPath("$.items[0].reason").value("Zweryfikowana na recepcji"))
			// Registering is the owner's own step.
			.andExpect(jsonPath("$.items[1].actor.accountId").value(client.toString()));
		history(ADMIN_TOKEN, "query", DOMAIN, "actorId", admin.toString())
			.andExpect(jsonPath("$.items[*].type").value(contains("ROLE_GRANTED", "STATUS_CHANGED")));
		history(ADMIN_TOKEN, "query", DOMAIN, "type", "ROLE_GRANTED", "type", "ROLE_REVOKED")
			.andExpect(jsonPath("$.items[*].account.accountId").value(contains(trainer.toString())));
		history(ADMIN_TOKEN, "query", "zielińska katarzyna").andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.items[*].account.accountId").value(everyItem(is(client.toString()))));
		history(ADMIN_TOKEN, "query", DOMAIN, "from", "2026-10-02", "to", "2026-10-02")
			.andExpect(jsonPath("$.items[*].toStatus").value(contains("ACTIVE")));
		history(ADMIN_TOKEN, "query", DOMAIN, "from", "2026-10-03")
			.andExpect(jsonPath("$.items[*].type").value(contains("ROLE_GRANTED")));
	}

	@Test
	void listsThePeopleWhoChangedAccounts() throws Exception {
		addStatusChange(client, null, "PENDING_APPROVAL", null, null, "2026-10-01T10:00:00Z");
		addStatusChange(client, "PENDING_APPROVAL", "ACTIVE", admin, null, "2026-10-02T10:00:00Z");

		perform(get("/api/v1/account-history/actors"), ADMIN_TOKEN).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].accountId").value(hasItem(admin.toString())))
			.andExpect(jsonPath("$.items[*].displayName").value(hasItem("Beata Lis")))
			// Only owners' own steps: not someone who changes other accounts.
			.andExpect(jsonPath("$.items[*].accountId").value(not(hasItem(client.toString()))));
	}

	@Test
	void rejectsAPeriodThatEndsBeforeItStartsAndUnknownTypes() throws Exception {
		history(ADMIN_TOKEN, "from", "2026-10-05", "to", "2026-10-01").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("to"));
		history(ADMIN_TOKEN, "type", "PASSWORD_CHANGED").andExpect(status().isBadRequest());
		history(ADMIN_TOKEN, "size", "101").andExpect(status().isBadRequest());
	}

	@Test
	void onlyAdministratorsSeeTheHistory() throws Exception {
		for (String token : new String[] { TRAINER_TOKEN, CLIENT_TOKEN }) {
			history(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
			perform(get("/api/v1/account-history/actors"), token).andExpect(status().isForbidden());
		}
	}

	private String invitationToken() {
		ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
		then(emailSender).should().send(sent.capture());
		Matcher token = TOKEN_IN_LINK.matcher(sent.getValue().textBody());
		assertThat(token.find()).isTrue();
		return token.group(1);
	}

	private ResultActions history(String token, String... params) throws Exception {
		MockHttpServletRequestBuilder request = get("/api/v1/account-history");
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		return perform(request, token);
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String token) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + token));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private UUID addAccount(String email, String roleCode, String firebaseUid, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", email)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", firebaseUid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id)
			.param("code", roleCode)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
		return id;
	}

	private void addStatusChange(UUID accountId, String from, String to, UUID changedBy, String reason,
			String changedAt) {
		jdbc.sql("""
				INSERT INTO account_status_change (account_id, from_status, to_status, changed_by, reason, changed_at)
				VALUES (:account, :from, :to, :changedBy, :reason, CAST(:changedAt AS timestamptz))
				""")
			.param("account", accountId)
			.param("from", from)
			.param("to", to)
			.param("changedBy", changedBy)
			.param("reason", reason)
			.param("changedAt", changedAt)
			.update();
	}

	private void addAudit(String module, String entityType, UUID entityId, String action, String changes,
			String occurredAt) {
		jdbc.sql("""
				INSERT INTO audit_log (occurred_at, actor_account_id, module, entity_type, entity_id, action, changes)
				VALUES (CAST(:occurredAt AS timestamptz), :actor, :module, :entityType, :entityId, :action,
				        CAST(:changes AS jsonb))
				""")
			.param("occurredAt", occurredAt)
			.param("actor", admin)
			.param("module", module)
			.param("entityType", entityType)
			.param("entityId", entityId)
			.param("action", action)
			.param("changes", changes)
			.update();
	}

}
