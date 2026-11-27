package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;

/**
 * Clients and trainers attach photos and documents to messages in every kind of conversation
 * (#149, #152). Files are kept in a local directory ({@code app.files.provider=LOCAL}); Firebase ID
 * tokens are mocked at the {@link JwtDecoder}.
 * <p>
 * The tests are not transactional, so requests run as in production; the data and the files are
 * removed after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MessageAttachmentTests {

	private static final String CONVERSATIONS = "/api/v1/me/conversations";

	private static final String EMAIL_DOMAIN = "@attachments.test";

	private static final String TITLE = "attachments-test";

	private static final String TRAINER = "attachments-trainer-token";

	private static final String CLIENT = "attachments-client-token";

	private static final String OTHER_CLIENT = "attachments-other-client-token";

	private static final String OUTSIDER = "attachments-outsider-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Value("${app.files.local-directory}")
	private Path storage;

	private UUID trainerAccount;

	private UUID trainer;

	private UUID clientAccount;

	private UUID client;

	private UUID otherClient;

	private UUID serviceType;

	private final List<String> conversations = new ArrayList<>();

	@BeforeEach
	void setUp() {
		trainerAccount = addAccount("trainer", "TRAINER", TRAINER);
		trainer = insertStaff(trainerAccount, "Anna", "Wiśniewska");
		clientAccount = addAccount("client", "CLIENT", CLIENT);
		client = insertCustomer(clientAccount, "Marta", "Zielińska");
		otherClient = insertCustomer(addAccount("other-client", "CLIENT", OTHER_CLIENT), "Kamil", "Nowak");
		insertCustomer(addAccount("outsider", "CLIENT", OUTSIDER), "Piotr", "Kaczmarek");
		jdbc.sql("""
				INSERT INTO staff_assignment (customer_id, staff_id, assigned_by)
				VALUES (:customer, :staff, :assignedBy)
				""")
			.param("customer", client)
			.param("staff", trainer)
			.param("assignedBy", trainerAccount)
			.update();
		serviceType = jdbc.sql("""
				INSERT INTO service_type (name, category, default_duration_minutes)
				VALUES (:name, 'GROUP_CLASS', 60)
				RETURNING id
				""")
			.param("name", TITLE)
			.query(UUID.class)
			.single();
	}

	@AfterEach
	void tearDown() throws IOException {
		String accounts = "SELECT id FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'";
		NotificationCleanup.deleteNotifications(jdbc, accounts);
		String chats = "SELECT id FROM conversation WHERE created_by IN (" + accounts + ")";
		String messages = "SELECT id FROM message WHERE conversation_id IN (" + chats + ")";
		jdbc.sql("DELETE FROM message_attachment WHERE message_id IN (" + messages + ")").update();
		jdbc.sql("DELETE FROM stored_file WHERE uploaded_by IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM message WHERE conversation_id IN (" + chats + ")").update();
		jdbc.sql("DELETE FROM conversation_participant WHERE conversation_id IN (" + chats + ")").update();
		jdbc.sql("DELETE FROM conversation WHERE created_by IN (" + accounts + ")").update();
		String events = "SELECT id FROM schedule_event WHERE title = '" + TITLE + "'";
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM event_staff WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM schedule_event WHERE title = '" + TITLE + "'").update();
		jdbc.sql("DELETE FROM service_type WHERE name = '" + TITLE + "'").update();
		jdbc.sql("DELETE FROM staff_assignment WHERE assigned_by IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'").update();
		for (String conversation : conversations) {
			FileSystemUtils.deleteRecursively(chatDirectory(conversation));
		}
	}

	@Test
	void aClientSendsAPhotoAndADocumentToTheirTrainer() throws Exception {
		String id = direct();
		byte[] photo = TestFiles.jpeg(6);
		byte[] results = TestFiles.pdf();

		String message = idOf(send(CLIENT, id, "  Wyniki badań i zdjęcie kolana  ",
				file("IMG_0042.jpg", photo), file("wyniki", results))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value("Wyniki badań i zdjęcie kolana"))
			.andExpect(jsonPath("$.attachments.length()").value(2))
			.andExpect(jsonPath("$.attachments[0].fileName").value("IMG_0042.jpg"))
			.andExpect(jsonPath("$.attachments[0].contentType").value("image/jpeg"))
			.andExpect(jsonPath("$.attachments[0].isImage").value(true))
			.andExpect(jsonPath("$.attachments[0].sizeBytes").value(photo.length))
			// Taken with the phone turned: shown as 100 x 200.
			.andExpect(jsonPath("$.attachments[0].width").value(100))
			.andExpect(jsonPath("$.attachments[0].height").value(200))
			.andExpect(jsonPath("$.attachments[1].fileName").value("wyniki.pdf"))
			.andExpect(jsonPath("$.attachments[1].contentType").value("application/pdf"))
			.andExpect(jsonPath("$.attachments[1].isImage").value(false))
			.andExpect(jsonPath("$.attachments[1].width").value(nullValue())));

		String messages = perform(TRAINER, get(CONVERSATIONS + "/" + id + "/messages"))
			.andExpect(jsonPath("$.items[0].id").value(message))
			.andExpect(jsonPath("$.items[0].attachments[*].fileName").value(contains("IMG_0042.jpg", "wyniki.pdf")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String photoId = JsonPath.read(messages, "$.items[0].attachments[0].id");
		String resultsId = JsonPath.read(messages, "$.items[0].attachments[1].id");
		perform(TRAINER, get(CONVERSATIONS)).andExpect(jsonPath("$.items[0].lastMessage.attachmentCount").value(2))
			.andExpect(jsonPath("$.items[0].lastMessage.imageCount").value(1));

		perform(TRAINER, get(attachment(id, message, photoId))).andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(content().bytes(photo))
			.andExpect(header().string("Content-Disposition", startsWith("inline;")))
			.andExpect(header().string("Cache-Control", "max-age=604800, private"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"));
		perform(CLIENT, get(attachment(id, message, resultsId))).andExpect(status().isOk())
			.andExpect(content().contentType("application/pdf"))
			.andExpect(content().bytes(results))
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"wyniki.pdf\""));
		// Only the people of the conversation, and only through the message the file belongs to.
		perform(OUTSIDER, get(attachment(id, message, photoId))).andExpect(status().isNotFound());
		perform(TRAINER, get(attachment(id, UUID.randomUUID().toString(), photoId))).andExpect(status().isNotFound());
		perform(TRAINER, get(attachment(id, message, UUID.randomUUID().toString()))).andExpect(status().isNotFound());

		assertThat(jdbc.sql("""
				SELECT owner_module || '|' || bucket || '|' || length(sha256) || '|' || uploaded_by
				FROM stored_file WHERE id = :id
				""").param("id", UUID.fromString(photoId)).query(String.class).single())
			.isEqualTo("communication|local|64|" + clientAccount);
		assertThat(storedFiles(id)).hasSize(2);
	}

	@Test
	void aTrainerSendsMaterialsWithoutText() throws Exception {
		String id = direct();

		String message = idOf(send(TRAINER, id, null, file("Plan na tydzień.docx", TestFiles.docx()),
				file("Obciążenia.xlsx", TestFiles.xlsx()), file("rozgrzewka.txt",
						"10 min rozgrzewki, potem rozciąganie".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value(nullValue()))
			.andExpect(jsonPath("$.attachments[*].contentType").value(contains(
					"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/plain"))));

		perform(CLIENT, get(CONVERSATIONS)).andExpect(jsonPath("$.items[0].lastMessage.body").value(nullValue()))
			.andExpect(jsonPath("$.items[0].lastMessage.attachmentCount").value(3))
			.andExpect(jsonPath("$.items[0].lastMessage.imageCount").value(0));
		String text = JsonPath.read(perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages")).andReturn()
			.getResponse()
			.getContentAsString(), "$.items[0].attachments[2].id");
		perform(CLIENT, get(attachment(id, message, text))).andExpect(status().isOk())
			.andExpect(content().contentType("text/plain;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"rozgrzewka.txt\""));

		// The push says what was sent when there is no text.
		await(() -> !notifications(clientAccount).isEmpty());
		assertThat(notifications(clientAccount)).containsExactly("Anna Wiśniewska|1 nowa wiadomość · 3 załączniki");
		send(TRAINER, id, null, file("cwiczenie.png", TestFiles.png())).andExpect(status().isCreated());
		await(() -> notifications(clientAccount).get(0).contains("2 nowe"));
		assertThat(notifications(clientAccount)).containsExactly("Anna Wiśniewska|2 nowe wiadomości · Zdjęcie");
	}

	@Test
	void membersOfAClassChatGetTheTrainersMaterialsUntilTheyLeave() throws Exception {
		UUID event = insertEvent();
		UUID reservation = reserve(event, client);
		reserve(event, otherClient);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/group-chats"),
				"{\"eventId\":\"" + event + "\",\"clientsCanReply\":false}")
			.andExpect(status().isCreated()));
		conversations.add(id);

		String before = idOf(send(TRAINER, id, "Program warsztatów", file("program.pdf", TestFiles.pdf()))
			.andExpect(status().isCreated()));
		String beforeFile = attachmentId(OTHER_CLIENT, id, 0);
		perform(OTHER_CLIENT, get(attachment(id, before, beforeFile))).andExpect(status().isOk());
		perform(CLIENT, get(attachment(id, before, beforeFile))).andExpect(status().isOk());
		// Clients may not reply in this chat, with files neither, and nothing is stored.
		send(CLIENT, id, null, file("a.jpg", TestFiles.jpeg(1))).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CONVERSATION_READ_ONLY"));

		jdbc.sql("UPDATE reservation SET status = 'CANCELLED' WHERE id = :id").param("id", reservation).update();
		// Opening the list brings the client's membership up to date: they have left.
		perform(CLIENT, get(CONVERSATIONS)).andExpect(jsonPath("$.items[0].hasLeft").value(true));
		String after = idOf(send(TRAINER, id, null, file("mapa.png", TestFiles.png())));
		String afterFile = attachmentId(OTHER_CLIENT, id, 1);
		// The client who left keeps what was sent before, but not what came after.
		perform(CLIENT, get(attachment(id, before, beforeFile))).andExpect(status().isOk());
		perform(CLIENT, get(attachment(id, after, afterFile))).andExpect(status().isNotFound());
		perform(OTHER_CLIENT, get(attachment(id, after, afterFile))).andExpect(status().isOk());
		// The same in the conversation's photos and files.
		perform(CLIENT, get(attachments(id, "FILE", "")))
			.andExpect(jsonPath("$.items[*].attachment.fileName").value(contains("program.pdf")));
		perform(CLIENT, get(attachments(id, "PHOTO", ""))).andExpect(jsonPath("$.items").value(empty()));
		perform(OTHER_CLIENT, get(attachments(id, "PHOTO", "")))
			.andExpect(jsonPath("$.items[*].attachment.fileName").value(contains("mapa.png")));
		assertThat(jdbc.sql("SELECT count(*) FROM stored_file WHERE uploaded_by = :client")
			.param("client", clientAccount)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void onlyPhotosAndDocumentsOfAtMostTenMegabytesFiveAtATime() throws Exception {
		String id = direct();

		send(CLIENT, id, "Zobacz", file("photo.png", "not really a photo".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"))
			.andExpect(jsonPath("$.allowedTypes").value(hasItem("pdf")));
		send(CLIENT, id, null, file("lista.csv", "a;b".getBytes(StandardCharsets.UTF_8)))
			.andExpect(status().isUnprocessableContent());
		send(CLIENT, id, null, file("big.pdf", new byte[10 * 1024 * 1024 + 1]))
			.andExpect(status().is(413))
			.andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
		send(CLIENT, id, null, file("empty.pdf", new byte[0])).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("files"));
		MockMultipartFile[] six = new MockMultipartFile[6];
		for (int i = 0; i < six.length; i++) {
			six[i] = file(i + ".pdf", TestFiles.pdf());
		}
		send(CLIENT, id, null, six).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("files"))
			.andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
		send(CLIENT, id, "   ", new MockMultipartFile[0]).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("body"))
			.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		send(CLIENT, id, "a".repeat(2001), file("a.pdf", TestFiles.pdf())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));

		// The first file was fine, the second is not: nothing is sent and the first is removed again.
		send(CLIENT, id, "Dwa pliki", file("ok.jpg", TestFiles.jpeg(1)), file("bad.exe", new byte[] { 'M', 'Z' }))
			.andExpect(status().isUnprocessableContent());
		perform(TRAINER, get(CONVERSATIONS + "/" + id + "/messages")).andExpect(jsonPath("$.items").value(empty()));
		assertThat(jdbc.sql("SELECT count(*) FROM stored_file WHERE uploaded_by = :client")
			.param("client", clientAccount)
			.query(Long.class)
			.single()).isZero();
		assertThat(storedFiles(id)).isEmpty();

		// Word and Excel files from before 2007 too.
		send(CLIENT, id, null, file("stary plan", TestFiles.doc()), file("tabela.xls", TestFiles.xls()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments[*].fileName").value(contains("stary plan.doc", "tabela.xls")))
			.andExpect(jsonPath("$.attachments[*].contentType")
				.value(contains("application/msword", "application/vnd.ms-excel")))
			.andExpect(jsonPath("$.attachments[*].isImage").value(contains(false, false)));

		// A HEIC photo straight from an iPhone is fine.
		send(CLIENT, id, null, file("IMG_0001.HEIC", TestFiles.heic())).andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments[0].contentType").value("image/heic"))
			.andExpect(jsonPath("$.attachments[0].isImage").value(true));
	}

	@Test
	void aPhotoIsStoredWithoutWhereItWasTaken() throws Exception {
		String id = direct();
		byte[] photo = TestFiles.jpegWithLocation();

		String message = idOf(send(CLIENT, id, null, file("IMG_0050.jpg", photo)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments[0].sizeBytes").value(photo.length))
			// The orientation stays: the photo is still shown upright, as 100 x 200.
			.andExpect(jsonPath("$.attachments[0].width").value(100))
			.andExpect(jsonPath("$.attachments[0].height").value(200)));
		String file = attachmentId(TRAINER, id, 0);

		byte[] stored = perform(TRAINER, get(attachment(id, message, file))).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(stored).hasSize(photo.length).isNotEqualTo(photo);
		assertThat(new String(stored, StandardCharsets.ISO_8859_1))
			.doesNotContain(new String(TestFiles.latitude(false), StandardCharsets.ISO_8859_1))
			.doesNotContain(TestFiles.XMP_LOCATION);
		// The checksum is of the stored copy.
		assertThat(jdbc.sql("SELECT sha256 FROM stored_file WHERE id = :id")
			.param("id", UUID.fromString(file))
			.query(String.class)
			.single()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stored)));
	}

	@Test
	void theConversationListsAllItsPhotosAndApartFromThemItsFiles() throws Exception {
		String id = direct();
		String first = idOf(send(CLIENT, id, "Kolano i wyniki", file("a.jpg", TestFiles.jpeg(6)),
				file("wyniki.pdf", TestFiles.pdf()), file("b.png", TestFiles.png())));
		send(TRAINER, id, "Dzięki, przejrzę").andExpect(status().isCreated());
		String second = idOf(send(TRAINER, id, null, file("c.heic", TestFiles.heic()),
				file("plan.docx", TestFiles.docx())));
		String mistake = idOf(send(CLIENT, id, null, file("pomyłka.jpg", TestFiles.jpeg(1))));
		perform(CLIENT, delete(CONVERSATIONS + "/" + id + "/messages/" + mistake)).andExpect(status().isNoContent());

		// Every photo of the conversation, oldest first, with the message it came with; deleted ones left out.
		String photos = perform(TRAINER, get(attachments(id, "PHOTO", ""))).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].attachment.fileName").value(contains("a.jpg", "b.png", "c.heic")))
			.andExpect(jsonPath("$.items[*].messageId").value(contains(first, first, second)))
			.andExpect(jsonPath("$.items[0].senderAccountId").value(clientAccount.toString()))
			.andExpect(jsonPath("$.items[0].senderName").value("Marta Zielińska"))
			.andExpect(jsonPath("$.items[0].isMine").value(false))
			.andExpect(jsonPath("$.items[2].isMine").value(true))
			.andExpect(jsonPath("$.items[0].attachment.isImage").value(true))
			.andExpect(jsonPath("$.items[0].attachment.width").value(100))
			.andExpect(jsonPath("$.hasMore").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString();
		perform(CLIENT, get(attachments(id, "FILE", "")))
			.andExpect(jsonPath("$.items[*].attachment.fileName").value(contains("wyniki.pdf", "plan.docx")))
			.andExpect(jsonPath("$.items[*].attachment.isImage").value(contains(false, false)))
			.andExpect(jsonPath("$.items[1].isMine").value(false));

		// Page by page, from the latest, or around a photo opened in the conversation.
		String a = JsonPath.read(photos, "$.items[0].attachment.id");
		String b = JsonPath.read(photos, "$.items[1].attachment.id");
		perform(TRAINER, get(attachments(id, "PHOTO", "&limit=2")))
			.andExpect(jsonPath("$.items[*].attachment.fileName").value(contains("b.png", "c.heic")))
			.andExpect(jsonPath("$.hasMore").value(true));
		perform(TRAINER, get(attachments(id, "PHOTO", "&before=" + b)))
			.andExpect(jsonPath("$.items[*].attachment.fileName").value(contains("a.jpg")))
			.andExpect(jsonPath("$.hasMore").value(false));
		perform(TRAINER, get(attachments(id, "PHOTO", "&after=" + a + "&limit=1")))
			.andExpect(jsonPath("$.items[*].attachment.fileName").value(contains("b.png")))
			.andExpect(jsonPath("$.hasMore").value(true));

		perform(OUTSIDER, get(attachments(id, "PHOTO", ""))).andExpect(status().isNotFound());
		perform(TRAINER, get(attachments(id, "PHOTO", "&before=" + UUID.randomUUID())))
			.andExpect(status().isNotFound());
		perform(TRAINER, get(attachments(id, "PHOTO", "&before=" + a + "&after=" + b)))
			.andExpect(status().isBadRequest());
		perform(TRAINER, get(attachments(id, "VIDEO", ""))).andExpect(status().isBadRequest());
	}

	@Test
	void aPhotoHasAThumbnailToShowInsteadOfTheWholePhoto() throws Exception {
		String id = direct();
		// Taken with the phone turned: stored as 2400 x 1800, shown as 1800 x 2400.
		byte[] photo = TestFiles.photo(2400, 1800, 6);

		byte[] icon = TestFiles.pngPhoto(64, 64);

		String message = idOf(send(CLIENT, id, null, file("IMG_0100.jpg", photo), file("ikona.png", icon))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.attachments[0].width").value(1800))
			.andExpect(jsonPath("$.attachments[0].hasThumbnail").value(true))
			// Its thumbnail would not be much smaller.
			.andExpect(jsonPath("$.attachments[1].hasThumbnail").value(false)));
		String messages = perform(TRAINER, get(CONVERSATIONS + "/" + id + "/messages"))
			.andExpect(jsonPath("$.items[0].attachments[*].hasThumbnail").value(contains(true, false)))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String photoId = JsonPath.read(messages, "$.items[0].attachments[0].id");
		String iconId = JsonPath.read(messages, "$.items[0].attachments[1].id");
		perform(TRAINER, get(attachments(id, "PHOTO", "")))
			.andExpect(jsonPath("$.items[0].attachment.hasThumbnail").value(true));

		byte[] thumbnail = perform(TRAINER, get(attachment(id, message, photoId) + "/thumbnail"))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(header().string("Cache-Control", "max-age=604800, private"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		// Upright and at most 1024 px on its longer side.
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumbnail));
		assertThat(image.getWidth()).isEqualTo(768);
		assertThat(image.getHeight()).isEqualTo(1024);
		assertThat(thumbnail.length).isLessThan(photo.length / 2);
		// The whole photo is still there for the full screen.
		perform(TRAINER, get(attachment(id, message, photoId))).andExpect(content().bytes(photo));
		perform(OUTSIDER, get(attachment(id, message, photoId) + "/thumbnail")).andExpect(status().isNotFound());
		perform(TRAINER, get(attachment(id, message, iconId) + "/thumbnail")).andExpect(status().isNotFound());
		assertThat(storedFiles(id)).hasSize(3);

		// Deleting the message deletes the thumbnail with the photo.
		perform(CLIENT, delete(CONVERSATIONS + "/" + id + "/messages/" + message)).andExpect(status().isNoContent());
		await(() -> storedFiles(id).isEmpty());
		assertThat(storedFiles(id)).isEmpty();
	}

	@Test
	void deletingAMessageDeletesItsFiles() throws Exception {
		String id = direct();
		String message = idOf(send(CLIENT, id, "Pomyłka", file("zdjecie.jpg", TestFiles.jpeg(1))));
		String file = attachmentId(TRAINER, id, 0);
		assertThat(storedFiles(id)).hasSize(1);

		perform(CLIENT, delete(CONVERSATIONS + "/" + id + "/messages/" + message)).andExpect(status().isNoContent());

		perform(TRAINER, get(CONVERSATIONS + "/" + id + "/messages"))
			.andExpect(jsonPath("$.items[0].isDeleted").value(true))
			.andExpect(jsonPath("$.items[0].attachments").value(empty()));
		perform(TRAINER, get(CONVERSATIONS)).andExpect(jsonPath("$.items[0].lastMessage.attachmentCount").value(0));
		perform(TRAINER, get(attachment(id, message, file))).andExpect(status().isNotFound());
		assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM stored_file WHERE id = :id")
			.param("id", UUID.fromString(file))
			.query(Boolean.class)
			.single()).isTrue();
		// The content goes once the deletion is committed.
		await(() -> storedFiles(id).isEmpty());
		assertThat(storedFiles(id)).isEmpty();
	}

	/** The client's 1:1 conversation with the trainer. */
	private String direct() throws Exception {
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}"));
		conversations.add(id);
		return id;
	}

	private ResultActions send(String token, String conversationId, String body, MockMultipartFile... files)
			throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipart(CONVERSATIONS + "/" + conversationId + "/messages");
		for (MockMultipartFile file : files) {
			request.file(file);
		}
		if (body != null) {
			request.param("body", body);
		}
		return perform(token, request);
	}

	private static MockMultipartFile file(String name, byte[] content) {
		// The declared type is ignored: the backend recognizes the type from the content.
		return new MockMultipartFile("files", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, content);
	}

	private static String attachment(String conversationId, String messageId, String fileId) {
		return CONVERSATIONS + "/" + conversationId + "/messages/" + messageId + "/attachments/" + fileId;
	}

	/** The conversation's photos or files, with more query parameters ({@code &...}). */
	private static String attachments(String conversationId, String kind, String query) {
		return CONVERSATIONS + "/" + conversationId + "/attachments?kind=" + kind + query;
	}

	/** The first attachment of the message at the given place, as the account sees the conversation. */
	private String attachmentId(String token, String conversationId, int message) throws Exception {
		String page = perform(token, get(CONVERSATIONS + "/" + conversationId + "/messages")).andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(page, "$.items[" + message + "].attachments[0].id");
	}

	private Path chatDirectory(String conversationId) {
		return storage.resolve("chat").resolve(conversationId);
	}

	/** The files of the conversation in the storage directory. */
	private List<Path> storedFiles(String conversationId) {
		Path directory = chatDirectory(conversationId);
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter((path) -> !path.getFileName().toString().startsWith(".")).toList();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** The account's message notifications, oldest first, as title|body. */
	private List<String> notifications(UUID account) {
		return jdbc.sql("""
				SELECT n.title || '|' || n.body
				FROM notification n
				JOIN notification_type t ON t.id = n.notification_type_id
				WHERE t.code = 'NEW_MESSAGE' AND n.recipient_id = :account
				ORDER BY n.scheduled_for
				""")
			.param("account", account)
			.query(String.class)
			.list();
	}

	/** Notifications are created, and deleted content removed, right after the commit. */
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
	}

	private static String idOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions perform(String token, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
		return mvc.perform(request.header("Authorization", "Bearer " + token));
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
		return perform(token, request.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private UUID addAccount(String name, String roleCode, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + EMAIL_DOMAIN)
			.query(UUID.class)
			.single();
		String uid = "attachments-" + id;
		jdbc.sql("INSERT INTO identity_link (account_id, provider, external_uid) VALUES (:id, 'FIREBASE', :uid)")
			.param("id", id)
			.param("uid", uid)
			.update();
		jdbc.sql("INSERT INTO account_role (account_id, role_id) SELECT :id, id FROM role WHERE code = :code")
			.param("id", id)
			.param("code", roleCode)
			.update();
		Jwt jwt = Jwt.withTokenValue(token).header("alg", "RS256").subject(uid).build();
		given(jwtDecoder.decode(token)).willReturn(jwt);
		return id;
	}

	private UUID insertStaff(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO staff_profile (account_id, first_name, last_name)
				VALUES (:accountId, :firstName, :lastName)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.query(UUID.class)
			.single();
	}

	private UUID insertCustomer(UUID accountId, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name, birth_date)
				VALUES (:accountId, 'ADULT', :firstName, :lastName, :birthDate)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("birthDate", LocalDate.of(1992, 6, 1))
			.query(UUID.class)
			.single();
	}

	private UUID insertEvent() {
		Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS);
		UUID event = jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, title, starts_at, ends_at, capacity)
				VALUES ('CLASS', :serviceType, :title, :startsAt, :endsAt, 12)
				RETURNING id
				""")
			.param("serviceType", serviceType)
			.param("title", TITLE)
			.param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
			.param("endsAt", startsAt.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
			.query(UUID.class)
			.single();
		jdbc.sql("INSERT INTO event_staff (event_id, staff_id) VALUES (:event, :staff)")
			.param("event", event)
			.param("staff", trainer)
			.update();
		return event;
	}

	private UUID reserve(UUID eventId, UUID customerId) {
		return jdbc.sql("""
				INSERT INTO reservation (event_id, customer_id, booked_by)
				VALUES (:event, :customer, :bookedBy)
				RETURNING id
				""")
			.param("event", eventId)
			.param("customer", customerId)
			.param("bookedBy", clientAccount)
			.query(UUID.class)
			.single();
	}

}
