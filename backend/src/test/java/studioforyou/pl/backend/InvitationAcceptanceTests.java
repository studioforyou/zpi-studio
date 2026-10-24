package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.identity.firebase.FirebaseUsers;
import studioforyou.pl.backend.identity.firebase.FirebaseUsers.FirebaseUser;

/**
 * Reading and accepting e-mail invitations. Firebase is mocked at {@link FirebaseUsers};
 * everything else, including the database, runs for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InvitationAcceptanceTests {

	private static final String TOKEN = "valid-token";

	private static final String PASSWORD_JSON = "{\"password\":\"Secret123!\"}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private FirebaseUsers firebaseUsers;

	private UUID invitedAccountId;

	private UUID invitationId;

	@BeforeEach
	void setUp() {
		UUID adminId = insertAccount("owner@example.com", "ACTIVE");
		invitedAccountId = insertAccount("julia@example.com", "INVITED");
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", invitedAccountId)
			.update();
		jdbc.sql("INSERT INTO customer_profile (account_id, first_name, last_name) VALUES (:id, 'Julia', 'Kamińska')")
			.param("id", invitedAccountId)
			.update();
		invitationId = insertInvitation(TOKEN, adminId, "now() + interval '7 days'", null);
		insertInvitation("old-token", adminId, "now() + interval '7 days'", null);
		insertInvitation("expired-token", adminId, "now() - interval '1 minute'", null);
		insertInvitation("used-token", adminId, "now() + interval '7 days'", "now()");
		given(firebaseUsers.createOrUpdate(anyString(), anyString())).willReturn(new FirebaseUser("uid-julia", true));
	}

	@Test
	void showsTheInvitation() throws Exception {
		mvc.perform(get("/api/v1/invitations/" + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("julia@example.com"))
			.andExpect(jsonPath("$.displayName").value("Julia Kamińska"))
			.andExpect(jsonPath("$.roles[0]").value("CLIENT"))
			.andExpect(jsonPath("$.expiresAt").exists());
	}

	@Test
	void unknownTokenIsNotFound() throws Exception {
		mvc.perform(get("/api/v1/invitations/no-such-token"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
	}

	@Test
	void expiredInvitationIsGone() throws Exception {
		mvc.perform(get("/api/v1/invitations/expired-token"))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("INVITATION_EXPIRED"));
	}

	@Test
	void usedInvitationIsGone() throws Exception {
		mvc.perform(get("/api/v1/invitations/used-token"))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
	}

	@Test
	void acceptingActivatesTheAccount() throws Exception {
		mvc.perform(post("/api/v1/invitations/" + TOKEN + "/accept").contentType(MediaType.APPLICATION_JSON)
			.content(PASSWORD_JSON)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("julia@example.com"));

		then(firebaseUsers).should().createOrUpdate("julia@example.com", "Secret123!");
		assertThat(jdbc.sql("SELECT status FROM account WHERE id = :id").param("id", invitedAccountId)
			.query(String.class).single()).isEqualTo("ACTIVE");
		assertThat(jdbc.sql("SELECT external_uid FROM identity_link WHERE account_id = :id AND provider = 'FIREBASE'")
			.param("id", invitedAccountId).query(String.class).single()).isEqualTo("uid-julia");
		assertThat(jdbc.sql("SELECT count(*) FROM account_status_change WHERE account_id = :id AND to_status = 'ACTIVE'")
			.param("id", invitedAccountId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT used_at IS NOT NULL FROM account_invitation WHERE id = :id").param("id", invitationId)
			.query(Boolean.class).single()).isTrue();
	}

	@Test
	void acceptedInvitationCannotBeUsedAgain() throws Exception {
		mvc.perform(post("/api/v1/invitations/" + TOKEN + "/accept").contentType(MediaType.APPLICATION_JSON)
			.content(PASSWORD_JSON)).andExpect(status().isOk());

		mvc.perform(post("/api/v1/invitations/" + TOKEN + "/accept").contentType(MediaType.APPLICATION_JSON)
			.content(PASSWORD_JSON)).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
		mvc.perform(get("/api/v1/invitations/old-token"))
			.andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("INVITATION_ALREADY_USED"));
	}

	@Test
	void shortPasswordIsRejected() throws Exception {
		mvc.perform(post("/api/v1/invitations/" + TOKEN + "/accept").contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"short\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		then(firebaseUsers).should(never()).createOrUpdate(anyString(), anyString());
	}

	private UUID insertAccount(String email, String status) {
		return jdbc.sql("INSERT INTO account (email, status) VALUES (:email, :status) RETURNING id")
			.param("email", email)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

	private UUID insertInvitation(String token, UUID sentBy, String expiresAt, String usedAt) {
		return jdbc.sql("""
				INSERT INTO account_invitation (account_id, token_hash, sent_by, expires_at, used_at)
				VALUES (:accountId, encode(sha256(convert_to(:token, 'UTF8')), 'hex'), :sentBy, %s, %s)
				RETURNING id
				""".formatted(expiresAt, usedAt == null ? "NULL" : usedAt))
			.param("accountId", invitedAccountId)
			.param("token", token)
			.param("sentBy", sentBy)
			.query(UUID.class)
			.single();
	}

}
