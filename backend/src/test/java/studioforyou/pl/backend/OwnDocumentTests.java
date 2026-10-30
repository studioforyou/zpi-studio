package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;


/**
 * Clients read and accept the documents of their own profile and of the children they are
 * guardians of, and manage their optional consents. Firebase ID tokens are mocked at the
 * {@link JwtDecoder}, as in the authentication tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnDocumentTests {

	private static final String PARENT_TOKEN = "parent-token";

	private static final String OTHER_TOKEN = "other-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID parentAccount;

	private UUID parent;

	private UUID child;

	private UUID poleDance;

	@BeforeEach
	void setUp() {
		parentAccount = addAccount("parent@example.com", "uid-parent", PARENT_TOKEN);
		UUID otherAccount = addAccount("other@example.com", "uid-other", OTHER_TOKEN);
		parent = insertCustomer("ADULT", "Katarzyna", "Zielińska", parentAccount);
		insertCustomer("ADULT", "Adam", "Mazur", otherAccount);
		child = insertCustomer("CHILD", "Zosia", "Zielińska", null);
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", parent)
			.param("child", child)
			.update();
		poleDance = insertService("doc-test-pole-dance");
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM document_acceptance").update();
		jdbc.sql("DELETE FROM document_service_requirement").update();
		jdbc.sql("DELETE FROM document_version").update();
		jdbc.sql("DELETE FROM document").update();
		jdbc.sql("DELETE FROM stored_file WHERE object_key LIKE 'doc-test/%'").update();
		jdbc.sql("DELETE FROM service_type WHERE name LIKE 'doc-test-%'").update();
		jdbc.sql("DELETE FROM guardianship").update();
		jdbc.sql("DELETE FROM customer_profile").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (SELECT id FROM account WHERE email LIKE '%@example.com')")
			.update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%@example.com'").update();
	}

	@Test
	void aClientSeesDocumentsInForceRequiredFirst() throws Exception {
		UUID marketing = insertDocument("MARKETING", "Newsletter", "MARKETING_EMAIL", "ADULT", false, false);
		UUID terms = insertDocument("TERMS", "Regulamin", "TERMS", "ALL", true, true);
		UUID termsV1 = insertVersion(terms, 1, "Regulamin v1", "200 days", true);
		UUID termsV2 = insertVersion(terms, 2, "Regulamin v2", "30 days", true);
		insertVersion(marketing, 1, "Zgoda na newsletter", "200 days", false);
		insertDraft(terms, 3);
		insertAcceptance(termsV1, parent);

		perform(PARENT_TOKEN, get(documentsOf(parent)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].title").value(contains("Regulamin", "Newsletter")))
			.andExpect(jsonPath("$.items[0].id").value(terms.toString()))
			.andExpect(jsonPath("$.items[0].kind").value("TERMS"))
			.andExpect(jsonPath("$.items[0].required").value(true))
			.andExpect(jsonPath("$.items[0].blocksBooking").value(true))
			.andExpect(jsonPath("$.items[0].versionId").value(termsV2.toString()))
			.andExpect(jsonPath("$.items[0].versionNo").value(2))
			.andExpect(jsonPath("$.items[0].status").value("REACCEPTANCE_REQUIRED"))
			.andExpect(jsonPath("$.items[0].acceptedVersionNo").value(1))
			.andExpect(jsonPath("$.items[0].revocable").value(false))
			.andExpect(jsonPath("$.items[0].content").doesNotExist())
			.andExpect(jsonPath("$.items[1].status").value("NOT_ACCEPTED"))
			.andExpect(jsonPath("$.items[1].revocable").value(false));
	}

	@Test
	void anEarlierAcceptanceStaysValidWhenTheNewVersionDoesNotAskAgain() throws Exception {
		UUID gdpr = insertDocument("GDPR", "RODO", "GDPR", "ALL", true, false);
		UUID v1 = insertVersion(gdpr, 1, "RODO v1", "200 days", true);
		insertVersion(gdpr, 2, "RODO v2 (typo fixed)", "10 days", false);
		insertAcceptance(v1, parent);

		perform(PARENT_TOKEN, get(documentsOf(parent)))
			.andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
			.andExpect(jsonPath("$.items[0].versionNo").value(2))
			.andExpect(jsonPath("$.items[0].acceptedVersionNo").value(1));
	}

	@Test
	void documentsFollowTheAudienceAndHideInactiveUnpublishedAndQuestionnaires() throws Exception {
		UUID adult = insertDocument("ADULT", "Dla dorosłych", "PARTICIPATION_CONSENT", "ADULT", true, true);
		UUID parental = insertDocument("PARENTAL", "Zgoda rodzica", "PARENTAL_CONSENT", "CHILD", true, true);
		UUID inactive = insertDocument("INACTIVE", "Nieaktywny", "TERMS", "ALL", true, true);
		UUID unpublished = insertDocument("DRAFT", "Szkic", "TERMS", "ALL", true, true);
		UUID questionnaire = insertDocument("HEALTH", "Ankieta", "HEALTH_QUESTIONNAIRE", "ALL", true, false);
		insertVersion(adult, 1, "a", "1 day", true);
		insertVersion(parental, 1, "p", "1 day", true);
		insertVersion(inactive, 1, "i", "1 day", true);
		insertVersion(questionnaire, 1, "q", "1 day", true);
		insertDraft(unpublished, 1);
		jdbc.sql("UPDATE document SET is_active = FALSE WHERE id = :id").param("id", inactive).update();

		perform(PARENT_TOKEN, get(documentsOf(parent)))
			.andExpect(jsonPath("$.items[*].title").value(contains("Dla dorosłych")));
		perform(PARENT_TOKEN, get(documentsOf(child)))
			.andExpect(jsonPath("$.items[*].title").value(contains("Zgoda rodzica")));
		perform(PARENT_TOKEN, get(documentsOf(parent) + "/" + parental)).andExpect(status().isNotFound());
	}

	@Test
	void serviceSpecificDocumentsNameTheirServices() throws Exception {
		UUID pole = insertDocument("POLE", "Zgoda pole dance", "PARTICIPATION_CONSENT", "ALL", true, true);
		insertVersion(pole, 1, "Ryzyko", "1 day", true);
		requireFor(pole, poleDance);

		perform(PARENT_TOKEN, get(documentsOf(parent)))
			.andExpect(jsonPath("$.items[0].requiredFor", hasSize(1)))
			.andExpect(jsonPath("$.items[0].requiredFor[0].id").value(poleDance.toString()))
			.andExpect(jsonPath("$.items[0].requiredFor[0].name").value("doc-test-pole-dance"));
	}

	@Test
	void oneDocumentComesWithTheTextInForce() throws Exception {
		UUID terms = insertDocument("TERMS", "Regulamin", "TERMS", "ALL", true, true);
		insertVersion(terms, 1, "Regulamin v1", "200 days", true);
		insertVersion(terms, 2, "Regulamin v2", "30 days", true);
		UUID pdf = insertDocument("PDF", "Zgoda (PDF)", "PARTICIPATION_CONSENT", "ALL", true, true);
		insertFileVersion(pdf);

		perform(PARENT_TOKEN, get(documentsOf(parent) + "/" + terms))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("Regulamin v2"))
			.andExpect(jsonPath("$.hasFile").value(false));
		perform(PARENT_TOKEN, get(documentsOf(parent) + "/" + pdf))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").doesNotExist())
			.andExpect(jsonPath("$.hasFile").value(true));
	}

	@Test
	void aClientAcceptsTheVersionInForce() throws Exception {
		UUID terms = insertDocument("TERMS", "Regulamin", "TERMS", "ALL", true, true);
		UUID v1 = insertVersion(terms, 1, "Regulamin v1", "200 days", true);
		UUID v2 = insertVersion(terms, 2, "Regulamin v2", "30 days", true);
		insertAcceptance(v1, parent);

		perform(PARENT_TOKEN, accept(parent, terms, v2))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.acceptedVersionNo").value(2))
			.andExpect(jsonPath("$.acceptedAt").isNotEmpty())
			.andExpect(jsonPath("$.content").value("Regulamin v2"));

		assertThat(jdbc.sql("""
				SELECT accepted_by FROM document_acceptance
				WHERE document_version_id = :version AND customer_id = :customer AND source = 'APP'
				""").param("version", v2).param("customer", parent).query(UUID.class).single())
			.isEqualTo(parentAccount);
		assertThat(jdbc.sql("SELECT count(*) FROM document_acceptance WHERE revoked_at IS NULL")
			.query(Long.class).single()).as("the earlier acceptance stays in the history").isEqualTo(2);
	}

	@Test
	void anOutdatedOrAlreadyAcceptedVersionIsRejected() throws Exception {
		UUID terms = insertDocument("TERMS", "Regulamin", "TERMS", "ALL", true, true);
		UUID v1 = insertVersion(terms, 1, "Regulamin v1", "200 days", true);
		UUID v2 = insertVersion(terms, 2, "Regulamin v2", "30 days", true);

		perform(PARENT_TOKEN, accept(parent, terms, v1))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		perform(PARENT_TOKEN, accept(parent, terms, v2)).andExpect(status().isCreated());
		perform(PARENT_TOKEN, accept(parent, terms, v2))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DOCUMENT_ALREADY_ACCEPTED"));
		perform(PARENT_TOKEN, post(documentsOf(parent) + "/" + terms + "/acceptances")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void aGuardianAcceptsForTheChild() throws Exception {
		UUID parental = insertDocument("PARENTAL", "Zgoda rodzica", "PARENTAL_CONSENT", "CHILD", true, true);
		UUID v1 = insertVersion(parental, 1, "Zgadzam się", "1 day", true);

		perform(PARENT_TOKEN, accept(child, parental, v1))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));

		assertThat(jdbc.sql("SELECT accepted_by FROM document_acceptance WHERE customer_id = :child")
			.param("child", child).query(UUID.class).single()).isEqualTo(parentAccount);

		jdbc.sql("UPDATE guardianship SET ended_at = now()").update();
		perform(PARENT_TOKEN, get(documentsOf(child))).andExpect(status().isNotFound());
	}

	@Test
	void anOptionalConsentCanBeWithdrawnAndGivenAgain() throws Exception {
		UUID marketing = insertDocument("MARKETING", "Newsletter", "MARKETING_EMAIL", "ADULT", false, false);
		UUID v1 = insertVersion(marketing, 1, "Zgoda", "1 day", false);

		perform(PARENT_TOKEN, accept(parent, marketing, v1))
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.revocable").value(true));
		perform(PARENT_TOKEN, delete(documentsOf(parent) + "/" + marketing + "/acceptances"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("NOT_ACCEPTED"))
			.andExpect(jsonPath("$.revocable").value(false));
		perform(PARENT_TOKEN, delete(documentsOf(parent) + "/" + marketing + "/acceptances"))
			.andExpect(status().isOk());
		perform(PARENT_TOKEN, accept(parent, marketing, v1))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));

		assertThat(jdbc.sql("SELECT count(*) FROM document_acceptance WHERE revoked_at IS NOT NULL")
			.query(Long.class).single()).as("the withdrawn consent stays in the history").isEqualTo(1);
	}

	@Test
	void aRequiredDocumentCannotBeWithdrawn() throws Exception {
		UUID terms = insertDocument("TERMS", "Regulamin", "TERMS", "ALL", true, true);
		UUID v1 = insertVersion(terms, 1, "Regulamin", "1 day", true);
		insertAcceptance(v1, parent);

		perform(PARENT_TOKEN, delete(documentsOf(parent) + "/" + terms + "/acceptances"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("DOCUMENT_REVOCATION_NOT_ALLOWED"));
	}

	@Test
	void otherProfilesAndDocumentsAreNotFound() throws Exception {
		UUID terms = insertDocument("TERMS", "Regulamin", "TERMS", "ALL", true, true);
		UUID v1 = insertVersion(terms, 1, "Regulamin", "1 day", true);

		perform(OTHER_TOKEN, get(documentsOf(parent)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		perform(OTHER_TOKEN, accept(child, terms, v1)).andExpect(status().isNotFound());
		perform(OTHER_TOKEN, get(documentsOf(UUID.randomUUID()))).andExpect(status().isNotFound());
		perform(PARENT_TOKEN, get(documentsOf(parent) + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
		mvc.perform(get(documentsOf(parent))).andExpect(status().isUnauthorized());
	}

	private static String documentsOf(UUID profile) {
		return "/api/v1/me/profiles/" + profile + "/documents";
	}

	private static MockHttpServletRequestBuilder accept(UUID profile, UUID document, UUID version) {
		return post(documentsOf(profile) + "/" + document + "/acceptances").contentType(MediaType.APPLICATION_JSON)
			.content("{\"versionId\":\"" + version + "\"}");
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + token));
	}

	private UUID insertDocument(String code, String title, String kind, String audience, boolean required,
			boolean blocksBooking) {
		return jdbc.sql("""
				INSERT INTO document (code, title, kind, audience, is_required, blocks_booking)
				VALUES (:code, :title, :kind, :audience, :required, :blocksBooking)
				RETURNING id
				""")
			.param("code", "TEST_" + code)
			.param("title", title)
			.param("kind", kind)
			.param("audience", audience)
			.param("required", required)
			.param("blocksBooking", blocksBooking)
			.query(UUID.class)
			.single();
	}

	/** A version published the given interval ago, e.g. {@code "30 days"}. */
	private UUID insertVersion(UUID document, int versionNo, String content, String publishedAgo,
			boolean requiresReacceptance) {
		return jdbc.sql("""
				INSERT INTO document_version (document_id, version_no, content, requires_reacceptance, published_at)
				VALUES (:document, :versionNo, :content, :reaccept, now() - CAST(:ago AS INTERVAL) - INTERVAL '1 second')
				RETURNING id
				""")
			.param("document", document)
			.param("versionNo", versionNo)
			.param("content", content)
			.param("reaccept", requiresReacceptance)
			.param("ago", publishedAgo)
			.query(UUID.class)
			.single();
	}

	private void insertDraft(UUID document, int versionNo) {
		jdbc.sql("INSERT INTO document_version (document_id, version_no, content) VALUES (:document, :no, 'draft')")
			.param("document", document)
			.param("no", versionNo)
			.update();
	}

	private void insertFileVersion(UUID document) {
		UUID file = jdbc.sql("""
				INSERT INTO stored_file (owner_module, bucket, object_key, original_name, mime_type, size_bytes)
				VALUES ('documents', 'test', 'doc-test/consent.pdf', 'consent.pdf', 'application/pdf', 1024)
				RETURNING id
				""").query(UUID.class).single();
		jdbc.sql("""
				INSERT INTO document_version (document_id, version_no, file_id, published_at)
				VALUES (:document, 1, :file, now() - INTERVAL '1 day')
				""")
			.param("document", document)
			.param("file", file)
			.update();
	}

	private void insertAcceptance(UUID version, UUID customer) {
		jdbc.sql("""
				INSERT INTO document_acceptance (document_version_id, customer_id, accepted_by, accepted_at)
				VALUES (:version, :customer, :account, now() - INTERVAL '5 days')
				""")
			.param("version", version)
			.param("customer", customer)
			.param("account", parentAccount)
			.update();
	}

	private void requireFor(UUID document, UUID service) {
		jdbc.sql("INSERT INTO document_service_requirement (document_id, service_type_id) VALUES (:d, :s)")
			.param("d", document)
			.param("s", service)
			.update();
	}

	private UUID insertService(String name) {
		return jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, is_active)
				VALUES (:name, 'GROUP_CLASS', 60, TRUE)
				RETURNING id
				""").param("name", name).query(UUID.class).single();
	}

	private UUID addAccount(String email, String firebaseUid, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", email)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", firebaseUid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", id)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(firebaseUid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
		return id;
	}

	private UUID insertCustomer(String kind, String firstName, String lastName, UUID account) {
		return jdbc.sql("""
				INSERT INTO customer_profile (kind, first_name, last_name, account_id)
				VALUES (:kind, :firstName, :lastName, :account)
				RETURNING id
				""")
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("account", account, java.sql.Types.OTHER)
			.query(UUID.class)
			.single();
	}

}
