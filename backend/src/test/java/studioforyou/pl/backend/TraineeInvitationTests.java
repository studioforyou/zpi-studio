package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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
 * Trainers create client accounts for the people they start working with. Firebase ID tokens are
 * mocked at the {@link JwtDecoder} and the e-mail provider at the {@link EmailSender}; the
 * database runs for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TraineeInvitationTests {

	private static final String INVITATIONS = "/api/v1/me/trainees/invitations";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@MockitoBean
	private EmailSender emailSender;

	private UUID trainerAccount;

	private UUID trainer;

	@BeforeEach
	void setUp() {
		trainerAccount = insertActiveAccount("trainer@example.com", "uid-trainer", "TRAINER", "trainer-token");
		trainer = insertStaff(trainerAccount, "Marek", "Nowak", "ACTIVE");
		insertStaff(insertActiveAccount("inactive@example.com", "uid-inactive", "TRAINER", "inactive-token"), "Ewa",
				"Wiśniewska", "INACTIVE");
		insertActiveAccount("no-profile@example.com", "uid-no-profile", "TRAINER", "no-profile-token");
		insertActiveAccount("owner@example.com", "uid-admin", "ADMIN", "admin-token");
		insertActiveAccount("client@example.com", "uid-client", "CLIENT", "client-token");
	}

	@Test
	void aTrainerCreatesAClientAccountAndStartsWorkingWithThem() throws Exception {
		mvc.perform(invite("trainer-token", "Julia.Kaminska@example.com", " Julia ", "Kamińska "))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("Julia.Kaminska@example.com"))
			.andExpect(jsonPath("$.displayName").value("Julia Kamińska"))
			.andExpect(jsonPath("$.customerId").isNotEmpty())
			.andExpect(jsonPath("$.accountId").isNotEmpty())
			.andExpect(jsonPath("$.expiresAt").exists());

		Map<String, Object> account = jdbc.sql("""
				SELECT a.id, a.status, a.created_by, c.id AS customer_id, c.kind, c.first_name, c.created_by AS profile_by
				FROM account a JOIN customer_profile c ON c.account_id = a.id
				WHERE a.email = 'Julia.Kaminska@example.com'
				""").query().singleRow();
		assertThat(account).containsEntry("status", "INVITED")
			.containsEntry("created_by", trainerAccount)
			.containsEntry("kind", "ADULT")
			.containsEntry("first_name", "Julia")
			.containsEntry("profile_by", trainerAccount);
		assertThat(jdbc.sql("""
				SELECT r.code FROM account_role ar JOIN role r ON r.id = ar.role_id WHERE ar.account_id = :id
				""").param("id", account.get("id")).query(String.class).single()).isEqualTo("CLIENT");
		assertThat(jdbc.sql("""
				SELECT count(*) FROM staff_assignment
				WHERE customer_id = :customer AND staff_id = :trainer AND assigned_by = :by
				  AND scope = 'PERSONAL_TRAINING' AND ended_at IS NULL
				""").param("customer", account.get("customer_id")).param("trainer", trainer)
			.param("by", trainerAccount).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT sent_by FROM account_invitation WHERE account_id = :id")
			.param("id", account.get("id")).query(UUID.class).single()).isEqualTo(trainerAccount);

		ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
		then(emailSender).should().send(sent.capture());
		assertThat(sent.getValue().toEmail()).isEqualTo("Julia.Kaminska@example.com");
		assertThat(sent.getValue().textBody()).contains("Cześć Julia")
			.contains("Twój trener Marek Nowak założył Ci konto")
			.contains("token=");
		assertThat(sent.getValue().htmlBody()).contains("<strong>Marek Nowak</strong>");
	}

	@Test
	void theNewTraineeIsListedAsAwaitingActivationUntilTheyAccept() throws Exception {
		mvc.perform(invite("trainer-token", "julia@example.com", "Julia", "Kamińska")).andExpect(status().isCreated());

		mvc.perform(get("/api/v1/me/trainees").header("Authorization", "Bearer trainer-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].displayName").value("Julia Kamińska"))
			.andExpect(jsonPath("$.items[0].isActive").value(true))
			.andExpect(jsonPath("$.items[0].isAwaitingActivation").value(true));

		jdbc.sql("UPDATE account SET status = 'ACTIVE', activated_at = now() WHERE email = 'julia@example.com'")
			.update();
		mvc.perform(get("/api/v1/me/trainees").header("Authorization", "Bearer trainer-token"))
			.andExpect(jsonPath("$.items[0].isAwaitingActivation").value(false));
	}

	@Test
	void anExistingEmailIsAConflictAndNothingIsSent() throws Exception {
		mvc.perform(invite("trainer-token", "CLIENT@example.com", "Julia", "Kamińska"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));

		then(emailSender).should(never()).send(any());
		assertThat(jdbc.sql("SELECT count(*) FROM staff_assignment WHERE staff_id = :trainer")
			.param("trainer", trainer).query(Long.class).single()).isZero();
	}

	@Test
	void theTrainerNeedsAnActiveStaffProfile() throws Exception {
		mvc.perform(invite("no-profile-token", "julia@example.com", "Julia", "Kamińska"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(invite("inactive-token", "adam@example.com", "Adam", "Mazur"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STAFF_UNAVAILABLE"));

		then(emailSender).should(never()).send(any());
	}

	@Test
	void onlyTrainersCreateAccountsHere() throws Exception {
		for (String token : new String[] { "admin-token", "client-token" }) {
			mvc.perform(invite(token, "julia@example.com", "Julia", "Kamińska"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		}
		then(emailSender).should(never()).send(any());
	}

	@Test
	void theDataIsValidated() throws Exception {
		mvc.perform(invite("trainer-token", "not-an-email", " ", "Kamińska"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors", hasSize(2)));
		then(emailSender).should(never()).send(any());
	}

	@Test
	void nothingIsSavedWhenTheEmailFails() throws Exception {
		willThrow(new EmailSendingException("down", null)).given(emailSender).send(any());

		mvc.perform(invite("trainer-token", "julia@example.com", "Julia", "Kamińska"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("EMAIL_NOT_SENT"));

		// The failed request marked the shared test transaction rollback-only, so the account, the
		// profile and the assignment are never saved.
		TestTransaction.flagForCommit();
		assertThatThrownBy(TestTransaction::end).isInstanceOf(UnexpectedRollbackException.class);
	}

	private MockHttpServletRequestBuilder invite(String token, String email, String firstName, String lastName) {
		return post(INVITATIONS).header("Authorization", "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"email": "%s", "firstName": "%s", "lastName": "%s"}
					""".formatted(email, firstName, lastName));
	}

	private UUID insertActiveAccount(String email, String uid, String role, String token) {
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
		given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID insertStaff(UUID accountId, String firstName, String lastName, String status) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name, status)
				VALUES (:accountId, :firstName, :lastName, :status)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("status", status)
			.query(UUID.class)
			.single();
	}

}
