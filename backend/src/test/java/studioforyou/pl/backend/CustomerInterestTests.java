package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service types a client marks to hear when booking of their classes opens (#143). The JWT
 * decoder is mocked; authorization and PostgreSQL run for real, each test in a rolled back
 * transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class CustomerInterestTests {

	private static final String CLIENT = "interest-client-token";

	private static final String OTHER_CLIENT = "interest-other-token";

	private static final String TRAINER = "interest-trainer-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID own;

	private UUID child;

	private UUID other;

	private UUID yoga;

	private UUID pilates;

	@BeforeEach
	void setUp() {
		own = addProfile(addAccount("client", CLIENT, "CLIENT"), "ADULT", "Anna");
		child = addProfile(null, "CHILD", "Jan");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", own).param("child", child).update();
		other = addProfile(addAccount("other", OTHER_CLIENT, "CLIENT"), "ADULT", "Piotr");
		addAccount("trainer", TRAINER, "TRAINER");
		yoga = addService("Interest yoga", true);
		pilates = addService("Interest pilates", true);
	}

	@Test
	void clientMarksAndUnmarksServiceTypesForThemselvesAndTheirChild() throws Exception {
		perform(CLIENT, get(interests(own))).andExpect(status().isOk())
			.andExpect(jsonPath("$.serviceTypeIds").isEmpty());

		perform(CLIENT, put(interests(own) + "/" + yoga)).andExpect(status().isOk())
			.andExpect(jsonPath("$.serviceTypeIds[0]").value(yoga.toString()));
		// Marking again changes nothing.
		perform(CLIENT, put(interests(own) + "/" + yoga)).andExpect(status().isOk())
			.andExpect(jsonPath("$.serviceTypeIds.length()").value(1));
		perform(CLIENT, put(interests(child) + "/" + pilates)).andExpect(status().isOk())
			.andExpect(jsonPath("$.serviceTypeIds[0]").value(pilates.toString()));

		assertThat(marked(own)).containsExactly(yoga);
		assertThat(marked(child)).containsExactly(pilates);

		perform(CLIENT, delete(interests(own) + "/" + yoga)).andExpect(status().isOk())
			.andExpect(jsonPath("$.serviceTypeIds").isEmpty());
		perform(CLIENT, delete(interests(own) + "/" + yoga)).andExpect(status().isOk());
		assertThat(marked(own)).isEmpty();
	}

	@Test
	void anotherClientsProfileAnUnknownOrInactiveServiceAndATrainerAreRefused() throws Exception {
		perform(CLIENT, get(interests(other))).andExpect(status().isNotFound());
		perform(CLIENT, put(interests(other) + "/" + yoga)).andExpect(status().isNotFound());
		perform(CLIENT, put(interests(own) + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
		perform(CLIENT, put(interests(own) + "/" + addService("Interest retired", false)))
			.andExpect(status().isNotFound());
		perform(TRAINER, get(interests(own))).andExpect(status().isForbidden());
		perform(null, get(interests(own))).andExpect(status().isUnauthorized());

		assertThat(marked(own)).isEmpty();
		assertThat(marked(other)).isEmpty();
	}

	private static String interests(UUID profile) {
		return "/api/v1/me/customer-profiles/" + profile + "/interests";
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request) throws Exception {
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return mvc.perform(request);
	}

	private java.util.List<UUID> marked(UUID profile) {
		return jdbc.sql("SELECT service_type_id FROM customer_interest WHERE customer_id = :id")
			.param("id", profile).query(UUID.class).list();
	}

	private UUID addService(String name, boolean active) {
		return jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes, is_active)
				VALUES (:name, 'GROUP', 60, :active) RETURNING id
				""").param("name", name).param("active", active).query(UUID.class).single();
	}

	private UUID addAccount(String name, String token, String role) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", "customer-interest-" + name + "@example.com").query(UUID.class).single();
		String uid = "uid-customer-interest-" + name;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id).param("uid", uid).update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :role")
			.param("id", id).param("role", role).update();
		given(jwtDecoder.decode(token))
			.willReturn(Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build());
		return id;
	}

	private UUID addProfile(UUID account, String kind, String firstName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name)
				VALUES (:account, :kind, :firstName, 'Tester') RETURNING id
				""").param("account", account, java.sql.Types.OTHER).param("kind", kind).param("firstName", firstName)
			.query(UUID.class).single();
	}

}
