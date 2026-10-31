package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import studioforyou.pl.backend.identity.firebase.FirebaseUsers;

/**
 * Administrators list, edit, suspend and close accounts of clients and staff. Firebase is mocked at
 * {@link FirebaseUsers}, ID tokens at the {@link JwtDecoder}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountManagementTests {

	private static final String DOMAIN = "@accounts.test";

	private static final String ADMIN_TOKEN = "accounts-admin-token";

	private static final String TRAINER_TOKEN = "accounts-trainer-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private FirebaseUsers firebaseUsers;

	private UUID admin;

	private UUID trainer;

	private UUID client;

	private UUID invited;

	@BeforeEach
	void setUp() {
		admin = addAccount("beata" + DOMAIN, "ACTIVE", "ADMIN", "uid-accounts-admin", ADMIN_TOKEN);
		addStaffProfile(admin, "Beata", "Lis");
		trainer = addAccount("marek" + DOMAIN, "ACTIVE", "TRAINER", "uid-accounts-trainer", TRAINER_TOKEN);
		addStaffProfile(trainer, "Marek", "Nowak");
		client = addAccount("kasia" + DOMAIN, "ACTIVE", "CLIENT", "uid-accounts-client", null);
		addCustomerProfile(client, "Katarzyna", "Zielińska", "+48600100101");
		invited = addAccount("julia" + DOMAIN, "INVITED", "CLIENT", null, null);
		addCustomerProfile(invited, "Julia", "Kamińska", null);
		jdbc.sql("""
				INSERT INTO account_invitation (account_id, token_hash, sent_by, expires_at)
				VALUES (:account, :hash, :admin, now() + interval '7 days')
				""")
			.param("account", invited)
			.param("hash", "a".repeat(64))
			.param("admin", admin)
			.update();
	}

	@AfterEach
	void tearDown() {
		String accounts = "(SELECT id FROM account WHERE email ILIKE '%" + DOMAIN + "' OR email ILIKE '%@closed.invalid')";
		for (String table : new String[] { "staff_profile", "customer_profile", "account_invitation",
				"account_status_change", "account_role", "identity_link" }) {
			jdbc.sql("DELETE FROM " + table + " WHERE account_id IN " + accounts).update();
		}
		jdbc.sql("DELETE FROM account WHERE id IN " + accounts).update();
	}

	@Test
	void listsAccountsWithTheirNamesAndRoles() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, get("/api/v1/accounts").param("query", DOMAIN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(4))
			// Sorted by last name: Kamińska, Lis, Nowak, Zielińska.
			.andExpect(jsonPath("$.items[0].displayName").value("Julia Kamińska"))
			.andExpect(jsonPath("$.items[0].status").value("INVITED"))
			.andExpect(jsonPath("$.items[1].roles[0]").value("ADMIN"))
			.andExpect(jsonPath("$.items[2].firstName").value("Marek"))
			.andExpect(jsonPath("$.items[3].phone").value("+48600100101"));
	}

	@Test
	void filtersByNameRoleAndStatus() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, get("/api/v1/accounts").param("query", "zielińska kat")))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(client.toString()));
		mvc.perform(as(ADMIN_TOKEN, get("/api/v1/accounts").param("query", DOMAIN).param("role", "CLIENT")))
			.andExpect(jsonPath("$.totalItems").value(2));
		mvc.perform(as(ADMIN_TOKEN,
				get("/api/v1/accounts").param("query", DOMAIN).param("role", "CLIENT").param("status", "INVITED")))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].email").value("julia" + DOMAIN));
		mvc.perform(as(ADMIN_TOKEN, get("/api/v1/accounts").param("role", "OWNER")))
			.andExpect(status().isBadRequest());
	}

	@Test
	void showsAnAccountWithItsPendingInvitation() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, get("/api/v1/accounts/{id}", invited)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.displayName").value("Julia Kamińska"))
			.andExpect(jsonPath("$.customerProfileId").isNotEmpty())
			.andExpect(jsonPath("$.staffProfileId").isEmpty())
			.andExpect(jsonPath("$.invitationExpiresAt").isNotEmpty())
			.andExpect(jsonPath("$.hasLogin").value(false));
		mvc.perform(as(ADMIN_TOKEN, get("/api/v1/accounts/{id}", UUID.randomUUID())))
			.andExpect(status().isNotFound());
	}

	@Test
	void updatesTheProfileAndTheFirebaseLogin() throws Exception {
		given(firebaseUsers.updateEmail("uid-accounts-client", "kasia.nowa" + DOMAIN)).willReturn(true);

		mvc.perform(as(ADMIN_TOKEN, put("/api/v1/accounts/{id}", client)).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "kasia.nowa%s", "firstName": " Kasia ", "lastName": "Nowa", "phone": "" }
					""".formatted(DOMAIN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("kasia.nowa" + DOMAIN))
			.andExpect(jsonPath("$.displayName").value("Kasia Nowa"))
			.andExpect(jsonPath("$.phone").isEmpty());

		then(firebaseUsers).should().updateEmail("uid-accounts-client", "kasia.nowa" + DOMAIN);
		assertThat(jdbc.sql("SELECT first_name FROM customer_profile WHERE account_id = :id")
			.param("id", client)
			.query(String.class)
			.single()).isEqualTo("Kasia");
	}

	@Test
	void updatesTheStaffProfileOfATrainer() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, put("/api/v1/accounts/{id}", trainer)).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "marek%s", "firstName": "Marek", "lastName": "Nowicki", "phone": "+48 600 200 202" }
					""".formatted(DOMAIN)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lastName").value("Nowicki"))
			.andExpect(jsonPath("$.phone").value("+48 600 200 202"));

		then(firebaseUsers).should(never()).updateEmail(anyString(), anyString());
	}

	@Test
	void rejectsAnEmailOfAnotherAccount() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, put("/api/v1/accounts/{id}", client)).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "MAREK%s", "firstName": "Katarzyna", "lastName": "Zielińska" }
					""".formatted(DOMAIN)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
	}

	@Test
	void rejectsAnEmailTakenInFirebaseAndKeepsTheOldOne() throws Exception {
		given(firebaseUsers.updateEmail(anyString(), anyString())).willReturn(false);

		mvc.perform(as(ADMIN_TOKEN, put("/api/v1/accounts/{id}", client)).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{ "email": "taken%s", "firstName": "Katarzyna", "lastName": "Zielińska" }
					""".formatted(DOMAIN)))
			.andExpect(status().isConflict());

		assertThat(email(client)).isEqualTo("kasia" + DOMAIN);
	}

	@Test
	void suspendsAndReactivatesAnAccount() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, post("/api/v1/accounts/{id}/suspend", client))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"reason\": \"Unpaid passes\" }"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		mvc.perform(as(ADMIN_TOKEN, post("/api/v1/accounts/{id}/suspend", client)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_STATUS_CONFLICT"));
		mvc.perform(as(ADMIN_TOKEN, post("/api/v1/accounts/{id}/reactivate", client)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		assertThat(jdbc.sql("""
				SELECT to_status || ':' || reason FROM account_status_change
				WHERE account_id = :id ORDER BY changed_at, to_status DESC
				""").param("id", client).query(String.class).list())
			.containsExactly("SUSPENDED:Unpaid passes", "ACTIVE:Reactivated by an administrator");
	}

	@Test
	void doesNotLetAnAdministratorLockThemselvesOut() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, post("/api/v1/accounts/{id}/suspend", admin)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("OWN_ACCOUNT_CHANGE"));
		mvc.perform(as(ADMIN_TOKEN, delete("/api/v1/accounts/{id}", admin)))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void closesAnAccountAndDeletesItsLogin() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, delete("/api/v1/accounts/{id}", trainer))).andExpect(status().isNoContent());

		Map<String, Object> account = jdbc.sql("""
				SELECT a.status, a.email, s.status AS profile_status,
				       EXISTS (SELECT 1 FROM identity_link l WHERE l.account_id = a.id) AS linked
				FROM account a JOIN staff_profile s ON s.account_id = a.id
				WHERE a.id = :id
				""").param("id", trainer).query().singleRow();
		assertThat(account).containsEntry("status", "CLOSED")
			.containsEntry("email", "closed-" + trainer + "@closed.invalid")
			.containsEntry("profile_status", "INACTIVE")
			.containsEntry("linked", false);
		then(firebaseUsers).should().delete("uid-accounts-trainer");

		// Closed accounts drop off the list, and the e-mail is free for a new invitation.
		mvc.perform(as(ADMIN_TOKEN, get("/api/v1/accounts").param("query", DOMAIN)))
			.andExpect(jsonPath("$.totalItems").value(3));
		mvc.perform(as(ADMIN_TOKEN, delete("/api/v1/accounts/{id}", trainer))).andExpect(status().isConflict());
	}

	@Test
	void closingAnInvitedAccountWithdrawsTheInvitation() throws Exception {
		mvc.perform(as(ADMIN_TOKEN, delete("/api/v1/accounts/{id}", invited))).andExpect(status().isNoContent());

		assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM account_invitation WHERE account_id = :id")
			.param("id", invited)
			.query(Boolean.class)
			.single()).isTrue();
		assertThat(jdbc.sql("SELECT status FROM customer_profile WHERE account_id = :id")
			.param("id", invited)
			.query(String.class)
			.single()).isEqualTo("ARCHIVED");
		then(firebaseUsers).should(never()).delete(anyString());
	}

	@Test
	void isOnlyForAdministrators() throws Exception {
		mvc.perform(as(TRAINER_TOKEN, get("/api/v1/accounts"))).andExpect(status().isForbidden());
		mvc.perform(as(TRAINER_TOKEN, delete("/api/v1/accounts/{id}", client))).andExpect(status().isForbidden());

		assertThat(email(client)).isEqualTo("kasia" + DOMAIN);
	}

	private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + token);
	}

	private String email(UUID accountId) {
		return jdbc.sql("SELECT email FROM account WHERE id = :id").param("id", accountId).query(String.class).single();
	}

	private UUID addAccount(String email, String status, String roleCode, String firebaseUid, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, :status) RETURNING id")
			.param("email", email)
			.param("status", status)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id)
			.param("code", roleCode)
			.update();
		if (firebaseUid != null) {
			jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
				.param("id", id)
				.param("uid", firebaseUid)
				.update();
		}
		if (token != null) {
			Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
			given(jwtDecoder.decode(token)).willReturn(jwt);
		}
		return id;
	}

	private void addStaffProfile(UUID accountId, String firstName, String lastName) {
		jdbc.sql("INSERT INTO staff_profile (account_id, first_name, last_name) VALUES (:id, :first, :last)")
			.param("id", accountId)
			.param("first", firstName)
			.param("last", lastName)
			.update();
	}

	private void addCustomerProfile(UUID accountId, String firstName, String lastName, String phone) {
		jdbc.sql("""
				INSERT INTO customer_profile (account_id, first_name, last_name, phone)
				VALUES (:id, :first, :last, :phone)
				""")
			.param("id", accountId)
			.param("first", firstName)
			.param("last", lastName)
			.param("phone", phone)
			.update();
	}

}
