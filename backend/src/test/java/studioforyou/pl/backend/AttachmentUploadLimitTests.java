package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Uploads over the multipart limits ({@code spring.servlet.multipart}) are refused by the server
 * before they reach the application, with {@code 413 FILE_TOO_LARGE}. Runs on a real server, as
 * MockMvc does not enforce these limits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AttachmentUploadLimitTests {

	private static final String EMAIL = "client@upload-limit.test";

	private static final String TOKEN = "upload-limit-token";

	private static final int MEGABYTE = 1024 * 1024;

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void setUp() {
		UUID account = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", EMAIL)
			.query(UUID.class)
			.single();
		String uid = "upload-limit-" + account;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", account)
			.param("uid", uid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", account)
			.update();
		given(jwtDecoder.decode(TOKEN)).willReturn(Jwt.withTokenValue(TOKEN).header("alg", "RS256").subject(uid).build());
	}

	@AfterEach
	void tearDown() {
		String account = "SELECT id FROM account WHERE email = '" + EMAIL + "'";
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + account + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + account + ")").update();
		jdbc.sql("DELETE FROM account WHERE email = '" + EMAIL + "'").update();
	}

	@Test
	void aFileOverTenMegabytesIsRefused() {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		form.add("files", file("skan.pdf", 10 * MEGABYTE + 1));

		assertFileTooLarge(form);
	}

	@Test
	void filesOverThirtyMegabytesTogetherAreRefused() {
		MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
		for (int i = 0; i < 4; i++) {
			form.add("files", file(i + ".pdf", 8 * MEGABYTE));
		}

		assertFileTooLarge(form);
	}

	private void assertFileTooLarge(MultiValueMap<String, Object> form) {
		RestClient client = RestClient.create("http://localhost:" + port);
		String[] result = client.post()
			.uri("/api/v1/me/conversations/{id}/messages", UUID.randomUUID())
			.header("Authorization", "Bearer " + TOKEN)
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(form)
			.exchange((request, response) -> new String[] { String.valueOf(response.getStatusCode().value()),
					response.bodyTo(String.class) });

		assertThat(result[0]).isEqualTo("413");
		assertThat((String) JsonPath.read(result[1], "$.code")).isEqualTo("FILE_TOO_LARGE");
	}

	private static ByteArrayResource file(String name, int size) {
		return new ByteArrayResource(new byte[size]) {
			@Override
			public String getFilename() {
				return name;
			}
		};
	}

}
