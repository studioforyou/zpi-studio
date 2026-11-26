package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.communication.notification.NotificationType;

/**
 * Message templates the administrator defines and edits (#160): reusable announcement templates,
 * and restoring the default content of a notification type, against real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AnnouncementTemplateTests {

	private static final String TEMPLATES = "/api/v1/announcement-templates";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void setUp() {
		addAccount("admin", "ADMIN");
		addAccount("client", "CLIENT");
		addAccount("trainer", "TRAINER");
	}

	// --- announcement templates ---

	@Test
	void theAdministratorDefinesEditsAndDeletesTemplates() throws Exception {
		String promotion = id(create("""
				{"name": " Promocja karnetów ", "title": " Promocja −20% ", "body": " Karnety taniej do niedzieli. ",
				 "marketing": true}
				""")
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", containsString(TEMPLATES + "/")))
			.andExpect(jsonPath("$.name").value("Promocja karnetów"))
			.andExpect(jsonPath("$.title").value("Promocja −20%"))
			.andExpect(jsonPath("$.body").value("Karnety taniej do niedzieli."))
			.andExpect(jsonPath("$.marketing").value(true))
			.andExpect(jsonPath("$.updatedAt").exists()));
		String closed = id(create("""
				{"name": "Studio zamknięte", "title": "Studio zamknięte", "body": "W święto studio jest zamknięte."}
				""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.marketing").value(false)));

		mvc.perform(withToken(get(TEMPLATES), "admin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(2)))
			.andExpect(jsonPath("$.items[0].name").value("Promocja karnetów"))
			.andExpect(jsonPath("$.items[1].name").value("Studio zamknięte"));

		update(closed, """
				{"body": "W poniedziałek studio jest zamknięte.", "marketing": false}
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Studio zamknięte"))
			.andExpect(jsonPath("$.title").value("Studio zamknięte"))
			.andExpect(jsonPath("$.body").value("W poniedziałek studio jest zamknięte."));
		update(promotion, "{\"marketing\": false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.marketing").value(false))
			.andExpect(jsonPath("$.title").value("Promocja −20%"));
		mvc.perform(withToken(get(TEMPLATES + "/" + promotion), "admin"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.marketing").value(false));

		mvc.perform(withToken(delete(TEMPLATES + "/" + promotion), "admin")).andExpect(status().isNoContent());
		mvc.perform(withToken(get(TEMPLATES), "admin"))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(closed));
		mvc.perform(withToken(delete(TEMPLATES + "/" + promotion), "admin")).andExpect(status().isNotFound());
	}

	@Test
	void namesAreUniqueRegardlessOfLetterCase() throws Exception {
		String first = id(create("{\"name\": \"Zmiana sali\", \"title\": \"T\", \"body\": \"B\"}")
			.andExpect(status().isCreated()));
		String second = id(create("{\"name\": \"Odwołanie\", \"title\": \"T\", \"body\": \"B\"}")
			.andExpect(status().isCreated()));

		create("{\"name\": \" zmiana SALI \", \"title\": \"T\", \"body\": \"B\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ANNOUNCEMENT_TEMPLATE_NAME_ALREADY_USED"));
		update(second, "{\"name\": \"ZMIANA SALI\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ANNOUNCEMENT_TEMPLATE_NAME_ALREADY_USED"));
		// Its own name in another letter case is not a conflict.
		update(first, "{\"name\": \"Zmiana Sali\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Zmiana Sali"));
	}

	@Test
	void rejectsMissingBlankAndTooLongContent() throws Exception {
		create("{\"title\": \"T\", \"body\": \"B\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		create("{\"name\": \"   \", \"title\": \"T\", \"body\": \"B\"}").andExpect(status().isBadRequest());
		create("{\"name\": \"N\", \"title\": \"" + "x".repeat(161) + "\", \"body\": \"B\"}")
			.andExpect(status().isBadRequest());
		create("{\"name\": \"N\", \"title\": \"T\", \"body\": \"" + "x".repeat(2001) + "\"}")
			.andExpect(status().isBadRequest());
		create("{\"name\": \"" + "x".repeat(101) + "\", \"title\": \"T\", \"body\": \"B\"}")
			.andExpect(status().isBadRequest());

		String id = id(create("{\"name\": \"N\", \"title\": \"T\", \"body\": \"B\"}").andExpect(status().isCreated()));
		update(id, "{\"title\": \"   \"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("title"));
		update(id, "{\"body\": \"\"}").andExpect(status().isBadRequest());
		update(UUID.randomUUID().toString(), "{\"title\": \"T\"}").andExpect(status().isNotFound());
		mvc.perform(withToken(get(TEMPLATES + "/" + UUID.randomUUID()), "admin")).andExpect(status().isNotFound());
	}

	@Test
	void onlyTheAdministratorManagesTemplates() throws Exception {
		for (String user : List.of("client", "trainer")) {
			mvc.perform(withToken(get(TEMPLATES), user)).andExpect(status().isForbidden());
			mvc.perform(withToken(post(TEMPLATES), user).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"N\", \"title\": \"T\", \"body\": \"B\"}")).andExpect(status().isForbidden());
		}
		mvc.perform(get(TEMPLATES)).andExpect(status().isUnauthorized());
		assertThat(jdbc.sql("SELECT count(*) FROM announcement_template").query(Long.class).single()).isZero();
	}

	// --- default content of notification types ---

	@Test
	void everyTypeStartsWithItsDefaultContent() throws Exception {
		String json = mvc.perform(withToken(get("/api/v1/notification-types"), "admin"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<String> codes = JsonPath.read(json, "$.items[*].code");
		assertThat(codes).containsExactlyInAnyOrderElementsOf(Arrays.stream(NotificationType.values())
			.filter(NotificationType::configurable).map(Enum::name).toList());
		for (String code : codes) {
			String type = "$.items[?(@.code == '" + code + "')]";
			List<String> titles = JsonPath.read(json, type + ".title");
			List<String> defaultTitles = JsonPath.read(json, type + ".defaultTitle");
			List<String> bodies = JsonPath.read(json, type + ".body");
			List<String> defaultBodies = JsonPath.read(json, type + ".defaultBody");
			assertThat(defaultTitles).as(code).containsExactlyElementsOf(titles).doesNotContainNull();
			assertThat(defaultBodies).as(code).containsExactlyElementsOf(bodies).doesNotContainNull();
		}
	}

	@Test
	void editedContentKeepsTheDefaultToRestore() throws Exception {
		String reminder = "/api/v1/notification-types/CLASS_REMINDER";
		mvc.perform(withToken(patch(reminder), "admin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\": \"Już {{ile przed}}!\", \"body\": \"{{zajęcia}} o {{godzina}}\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Już {{ile przed}}!"))
			.andExpect(jsonPath("$.defaultTitle").value("Przypomnienie o zajęciach"))
			.andExpect(jsonPath("$.defaultBody").value("{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}."));

		mvc.perform(withToken(patch(reminder), "admin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\": \"Przypomnienie o zajęciach\", "
					+ "\"body\": \"{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}.\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Przypomnienie o zajęciach"))
			.andExpect(jsonPath("$.body").value("{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}."));
	}

	// --- helpers ---

	private ResultActions create(String body) throws Exception {
		return mvc.perform(withToken(post(TEMPLATES), "admin").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions update(String id, String body) throws Exception {
		return mvc.perform(withToken(patch(TEMPLATES + "/" + id), "admin").contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static String id(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private void addAccount(String name, String... roles) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + "@templates.example.com").query(UUID.class).single();
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", "templates-" + name).update();
		for (String role : roles) {
			jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
				.param("id", id).param("code", role).update();
		}
		given(jwtDecoder.decode(name)).willReturn(Jwt.withTokenValue(name).header("alg", "RS256")
			.subject("templates-" + name).build());
	}

	private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
		return request.header("Authorization", "Bearer " + token);
	}

}
