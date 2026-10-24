package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.mail.EmailMessage;
import studioforyou.pl.backend.communication.mail.EmailSender;
import studioforyou.pl.backend.communication.mail.EmailSendingException;

/**
 * Sending invitations. Firebase ID tokens are mocked at the {@link JwtDecoder} and the e-mail
 * provider at the {@link EmailSender}; the database runs for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InvitationCreationTests {

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private EmailSender emailSender;

	private UUID adminId;

	@BeforeEach
	void setUp() {
		adminId = insertActiveAccount("owner@example.com", "uid-admin", "ADMIN");
		insertActiveAccount("trainer@example.com", "uid-trainer", "TRAINER");
		givenToken("admin-token", "uid-admin");
		givenToken("trainer-token", "uid-trainer");
	}

	@Test
	void adminInvitesAClient() throws Exception {
		mvc.perform(invite("admin-token", "Julia.Kaminska@example.com", "CLIENT"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("Julia.Kaminska@example.com"))
			.andExpect(jsonPath("$.expiresAt").exists());

		UUID accountId = jdbc.sql("SELECT id FROM account WHERE email = 'Julia.Kaminska@example.com' AND status = 'INVITED'")
			.query(UUID.class)
			.single();
		assertThat(jdbc.sql("SELECT r.code FROM account_role ar JOIN role r ON r.id = ar.role_id WHERE ar.account_id = :id")
			.param("id", accountId).query(String.class).single()).isEqualTo("CLIENT");
		assertThat(jdbc.sql("SELECT first_name FROM customer_profile WHERE account_id = :id").param("id", accountId)
			.query(String.class).single()).isEqualTo("Julia");
		assertThat(jdbc.sql("SELECT count(*) FROM account_status_change WHERE account_id = :id AND to_status = 'INVITED'")
			.param("id", accountId).query(Long.class).single()).isEqualTo(1);

		ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
		then(emailSender).should().send(sent.capture());
		assertThat(sent.getValue().toEmail()).isEqualTo("Julia.Kaminska@example.com");
		assertThat(sent.getValue().textBody()).contains("Cześć Julia");
		Matcher token = TOKEN_IN_LINK.matcher(sent.getValue().textBody());
		assertThat(token.find()).isTrue();
		assertThat(jdbc.sql("""
				SELECT count(*) FROM account_invitation
				WHERE account_id = :id AND sent_by = :admin AND expires_at > now()
				  AND token_hash = encode(sha256(convert_to(:token, 'UTF8')), 'hex')
				""").param("id", accountId).param("admin", adminId).param("token", token.group(1))
			.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void adminInvitesATrainer() throws Exception {
		mvc.perform(invite("admin-token", "marek@example.com", "TRAINER")).andExpect(status().isCreated());

		assertThat(jdbc.sql("""
				SELECT count(*) FROM staff_profile s JOIN account a ON a.id = s.account_id
				WHERE a.email = 'marek@example.com'
				""").query(Long.class).single()).isEqualTo(1);
		ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
		then(emailSender).should().send(sent.capture());
		assertThat(sent.getValue().toEmail()).isEqualTo("marek@example.com");
	}

	@Test
	void onlyAnAdminCanInvite() throws Exception {
		mvc.perform(invite("trainer-token", "julia@example.com", "CLIENT"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

		then(emailSender).should(never()).send(any());
	}

	@Test
	void existingEmailIsAConflict() throws Exception {
		mvc.perform(invite("admin-token", "TRAINER@example.com", "CLIENT"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
	}

	@Test
	void nothingIsSavedWhenTheEmailFails() throws Exception {
		willThrow(new EmailSendingException("down", null)).given(emailSender).send(any());

		mvc.perform(invite("admin-token", "julia@example.com", "CLIENT"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_SENT"));

		// The test transaction is shared with the request: committing it fails because the failed
		// invitation marked it rollback-only, so the account and invitation are never saved.
		TestTransaction.flagForCommit();
		assertThatThrownBy(TestTransaction::end).isInstanceOf(UnexpectedRollbackException.class);
	}

	private MockHttpServletRequestBuilder invite(String token, String email, String role) {
		return post("/api/v1/invitations").header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s", "firstName": "%s", "lastName": "Testowa", "role": "%s"}
					""".formatted(email, email.startsWith("marek") ? "Marek" : "Julia", role));
	}

	private UUID insertActiveAccount(String email, String uid, String role) {
		UUID id = jdbc.sql("INSERT INTO account (email, status, activated_at) VALUES (:email, 'ACTIVE', now()) RETURNING id")
			.param("email", email)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", uid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id)
			.param("role", role)
			.update();
		return id;
	}

	private void givenToken(String token, String uid) {
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
	}

}
