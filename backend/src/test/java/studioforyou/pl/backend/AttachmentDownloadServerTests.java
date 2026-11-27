package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

/**
 * A photo of a megabyte downloads in whole from a real server, with its length, as the app's
 * download expects. MockMvc does not stream the response like the server does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AttachmentDownloadServerTests {

	private static final String EMAIL = "client@download.test";

	private static final String TOKEN = "download-token";

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Value("${app.files.local-directory}")
	private Path storage;

	private UUID account;

	private UUID conversation;

	@BeforeEach
	void setUp() {
		account = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", EMAIL)
			.query(UUID.class)
			.single();
		String uid = "download-" + account;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", account)
			.param("uid", uid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = 'CLIENT'")
			.param("id", account)
			.update();
		given(jwtDecoder.decode(TOKEN)).willReturn(Jwt.withTokenValue(TOKEN).header("alg", "RS256").subject(uid).build());
		conversation = jdbc.sql("INSERT INTO conversation (kind, created_by) VALUES ('DIRECT', :account) RETURNING id")
			.param("account", account)
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO conversation_participant (conversation_id, account_id) VALUES (:c, :a)")
			.param("c", conversation)
			.param("a", account)
			.update();
	}

	@AfterEach
	void tearDown() {
		jdbc.sql("DELETE FROM message_attachment WHERE message_id IN (SELECT id FROM message WHERE conversation_id = :c)")
			.param("c", conversation)
			.update();
		jdbc.sql("DELETE FROM stored_file WHERE uploaded_by = :a").param("a", account).update();
		jdbc.sql("DELETE FROM message WHERE conversation_id = :c").param("c", conversation).update();
		jdbc.sql("DELETE FROM conversation_participant WHERE conversation_id = :c").param("c", conversation).update();
		jdbc.sql("DELETE FROM conversation WHERE id = :c").param("c", conversation).update();
		jdbc.sql("DELETE FROM account_role WHERE account_id = :a").param("a", account).update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id = :a").param("a", account).update();
		jdbc.sql("DELETE FROM account WHERE id = :a").param("a", account).update();
		FileSystemUtils.deleteRecursively(storage.resolve("chat").resolve(conversation.toString()).toFile());
	}

	@Test
	void downloadsAPhoto() throws Exception {
		byte[] photo = new byte[1_169_457];
		System.arraycopy(TestFiles.jpeg(1), 0, photo, 0, TestFiles.jpeg(1).length);
		UUID message = jdbc.sql("""
				INSERT INTO message (conversation_id, sender_id, body) VALUES (:c, :a, NULL) RETURNING id
				""").param("c", conversation).param("a", account).query(UUID.class).single();
		UUID file = UUID.randomUUID();
		String key = "chat/" + conversation + "/" + file;
		Files.createDirectories(storage.resolve(key).getParent());
		Files.write(storage.resolve(key), photo);
		jdbc.sql("""
				INSERT INTO stored_file (id, owner_module, uploaded_by, bucket, object_key, original_name, mime_type, size_bytes)
				VALUES (:id, 'communication', :a, 'local', :key, 'IMG.jpg', 'image/jpeg', :size)
				""").param("id", file).param("a", account).param("key", key).param("size", photo.length).update();
		jdbc.sql("INSERT INTO message_attachment (message_id, file_id, position) VALUES (:m, :f, 0)")
			.param("m", message)
			.param("f", file)
			.update();

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		HttpRequest request = HttpRequest
			.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/conversations/" + conversation + "/messages/"
					+ message + "/attachments/" + file))
			.header("Authorization", "Bearer " + TOKEN)
			.timeout(Duration.ofSeconds(10))
			.build();
		HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Length")).hasValue(String.valueOf(photo.length));
		assertThat(response.headers().firstValue("Content-Type")).hasValue("image/jpeg");
		assertThat(response.body()).isEqualTo(photo);
	}

}
