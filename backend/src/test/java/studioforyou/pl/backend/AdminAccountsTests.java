package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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

import studioforyou.pl.backend.identity.firebase.FirebaseUsers;

/**
 * Administrators create other administrators' accounts with a generated password. Firebase is mocked at
 * {@link FirebaseUsers}, ID tokens at the {@link JwtDecoder}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminAccountsTests {

	private static final String ADMIN_TOKEN = "admin-token";

	private static final String TRAINER_TOKEN = "trainer-token";

	private static final String NEW_ADMIN = """
			{ "email": "Nowa.Admin@example.com", "firstName": " Ola ", "lastName": "Nowak" }
			""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private FirebaseUsers firebaseUsers;

	private UUID admin;

	@BeforeEach
	void setUp() {
		admin = addAccount("admin@example.com", "uid-admin", "ADMIN", ADMIN_TOKEN);
		addAccount("trainer@example.com", "uid-trainer", "TRAINER", TRAINER_TOKEN);
	}

	@AfterEach
	void tearDown() {
		String accounts = "(SELECT id FROM account WHERE email ILIKE '%@example.com')";
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN " + accounts).update();
		jdbc.sql("DELETE FROM account_status_change WHERE account_id IN " + accounts).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN " + accounts).update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN " + accounts).update();
		jdbc.sql("DELETE FROM account WHERE email ILIKE '%@example.com'").update();
	}

	@Test
	void createsAnActiveAdministratorWithAGeneratedPassword() throws Exception {
		given(firebaseUsers.createNew(eq("Nowa.Admin@example.com"), anyString())).willReturn(Optional.of("uid-new"));

		String body = create(ADMIN_TOKEN, NEW_ADMIN).andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("Nowa.Admin@example.com"))
			.andExpect(jsonPath("$.accountId").isNotEmpty())
			.andExpect(jsonPath("$.staffProfileId").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		// The administrator sees the same password the Firebase user was created with.
		ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
		then(firebaseUsers).should().createNew(eq("Nowa.Admin@example.com"), password.capture());
		assertThat(password.getValue()).hasSize(16).isEqualTo(JsonPath.read(body, "$.password"));

		Map<String, Object> account = jdbc.sql("""
				SELECT a.status, a.created_by, a.activated_at IS NOT NULL AS activated, r.code AS role,
				       p.first_name, p.last_name, l.external_uid
				FROM account a
				JOIN account_role ar ON ar.account_id = a.id
				JOIN role r ON r.id = ar.role_id
				JOIN staff_profile p ON p.account_id = a.id
				JOIN identity_link l ON l.account_id = a.id
				WHERE a.email = 'Nowa.Admin@example.com'
				""").query().singleRow();
		assertThat(account).containsEntry("status", "ACTIVE")
			.containsEntry("created_by", admin)
			.containsEntry("activated", true)
			.containsEntry("role", "ADMIN")
			.containsEntry("first_name", "Ola")
			.containsEntry("last_name", "Nowak")
			.containsEntry("external_uid", "uid-new");
		assertThat(jdbc.sql("""
				SELECT to_status FROM account_status_change
				WHERE account_id = (SELECT id FROM account WHERE email = 'Nowa.Admin@example.com')
				""").query(String.class).single()).isEqualTo("ACTIVE");
	}

	@Test
	void rejectsAnEmailThatAlreadyHasAnAccount() throws Exception {
		create(ADMIN_TOKEN, NEW_ADMIN.replace("Nowa.Admin@example.com", "TRAINER@example.com"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));

		then(firebaseUsers).should(never()).createNew(anyString(), anyString());
	}

	@Test
	void doesNotTakeOverAnExistingFirebaseUser() throws Exception {
		given(firebaseUsers.createNew(anyString(), anyString())).willReturn(Optional.empty());

		create(ADMIN_TOKEN, NEW_ADMIN).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));

		assertThat(countAccounts("Nowa.Admin@example.com")).isZero();
	}

	@Test
	void deletesTheFirebaseUserWhenNothingIsSaved() throws Exception {
		// The UID is already linked to the trainer, so linking it again fails and rolls back.
		given(firebaseUsers.createNew(anyString(), anyString())).willReturn(Optional.of("uid-trainer"));

		create(ADMIN_TOKEN, NEW_ADMIN).andExpect(status().is5xxServerError());

		assertThat(countAccounts("Nowa.Admin@example.com")).isZero();
		then(firebaseUsers).should().delete("uid-trainer");
	}

	@Test
	void isOnlyForAdministrators() throws Exception {
		create(TRAINER_TOKEN, NEW_ADMIN).andExpect(status().isForbidden());

		then(firebaseUsers).should(never()).createNew(anyString(), anyString());
	}

	private ResultActions create(String token, String json) throws Exception {
		return mvc.perform(post("/api/v1/admin-accounts").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private long countAccounts(String email) {
		return jdbc.sql("SELECT count(*) FROM account WHERE email = :email")
			.param("email", email)
			.query(Long.class)
			.single();
	}

	private UUID addAccount(String email, String firebaseUid, String roleCode, String token) {
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

}
