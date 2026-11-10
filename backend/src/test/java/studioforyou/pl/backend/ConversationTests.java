package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.jayway.jsonpath.JsonPath;
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
 * Clients and trainers talk in 1:1 conversations, and trainers create the chats of their fixed
 * groups and classes. Firebase ID tokens are mocked at the {@link JwtDecoder}.
 * <p>
 * The tests are not transactional, so requests run as in production; the data is removed after
 * each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConversationTests {

	private static final String CONVERSATIONS = "/api/v1/me/conversations";

	private static final String EMAIL_DOMAIN = "@chat.test";

	private static final String TITLE = "chat-test";

	private static final String TRAINER = "chat-trainer-token";

	private static final String OTHER_TRAINER = "chat-other-trainer-token";

	private static final String RECEPTION = "chat-reception-token";

	private static final String CLIENT = "chat-client-token";

	private static final String PARENT = "chat-parent-token";

	private static final String OUTSIDER = "chat-outsider-token";

	private static final String ADMIN = "chat-admin-token";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private UUID trainerAccount;

	private UUID trainer;

	private UUID otherTrainer;

	private UUID receptionist;

	private UUID clientAccount;

	private UUID client;

	private UUID parentAccount;

	private UUID child;

	private UUID outsider;

	private UUID serviceType;

	@BeforeEach
	void setUp() {
		trainerAccount = addAccount("trainer", "TRAINER", TRAINER);
		trainer = insertStaff(trainerAccount, "Anna", "Wiśniewska");
		otherTrainer = insertStaff(addAccount("other-trainer", "TRAINER", OTHER_TRAINER), "Tomasz", "Sikora");
		// A staff member without the trainer role.
		receptionist = insertStaff(addAccount("reception", "RECEPTION", RECEPTION), "Ewa", "Lis");
		insertStaff(addAccount("admin", "ADMIN", ADMIN), "Beata", "Wrona");
		clientAccount = addAccount("client", "CLIENT", CLIENT);
		client = insertCustomer(clientAccount, "ADULT", "Marta", "Zielińska");
		parentAccount = addAccount("parent", "CLIENT", PARENT);
		UUID parent = insertCustomer(parentAccount, "ADULT", "Kamil", "Nowak");
		child = insertCustomer(null, "CHILD", "Zosia", "Nowak");
		jdbc.sql("INSERT INTO guardianship (guardian_id, child_id) VALUES (:guardian, :child)")
			.param("guardian", parent)
			.param("child", child)
			.update();
		outsider = insertCustomer(addAccount("outsider", "CLIENT", OUTSIDER), "ADULT", "Piotr", "Kaczmarek");
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
	void tearDown() {
		String accounts = "SELECT id FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'";
		NotificationCleanup.deleteNotifications(jdbc, accounts);
		String conversations = "SELECT id FROM conversation WHERE created_by IN (" + accounts + ")";
		jdbc.sql("DELETE FROM message WHERE conversation_id IN (" + conversations + ")").update();
		jdbc.sql("DELETE FROM conversation_participant WHERE conversation_id IN (" + conversations + ")").update();
		jdbc.sql("DELETE FROM conversation WHERE created_by IN (" + accounts + ")").update();
		String events = "SELECT id FROM schedule_event WHERE title = '" + TITLE + "'";
		jdbc.sql("DELETE FROM reservation WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM event_staff WHERE event_id IN (" + events + ")").update();
		jdbc.sql("DELETE FROM schedule_event WHERE title = '" + TITLE + "'").update();
		String groups = "SELECT id FROM training_group WHERE name LIKE '" + TITLE + "%'";
		jdbc.sql("DELETE FROM group_membership WHERE group_id IN (" + groups + ")").update();
		jdbc.sql("DELETE FROM training_group WHERE name LIKE '" + TITLE + "%'").update();
		jdbc.sql("DELETE FROM service_type WHERE name = '" + TITLE + "'").update();
		jdbc.sql("DELETE FROM staff_assignment WHERE assigned_by IN (" + accounts + ")").update();
		String profiles = "SELECT id FROM customer_profile WHERE created_by IS NULL AND last_name IN "
				+ "('Zielińska', 'Nowak', 'Kaczmarek') AND (account_id IS NULL OR account_id IN (" + accounts + "))";
		jdbc.sql("DELETE FROM guardianship WHERE child_id IN (" + profiles + ")").update();
		jdbc.sql("DELETE FROM customer_profile WHERE id IN (:ids)")
			.param("ids", List.of(client, child, outsider))
			.update();
		jdbc.sql("DELETE FROM customer_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM staff_profile WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account_role WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM identity_link WHERE account_id IN (" + accounts + ")").update();
		jdbc.sql("DELETE FROM account WHERE email LIKE '%" + EMAIL_DOMAIN + "'").update();
	}

	@Test
	void aTrainerAndTheirTraineeTalkOneToOne() throws Exception {
		assignTrainee(client);

		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("DIRECT"))
			.andExpect(jsonPath("$.title").value("Marta Zielińska"))
			.andExpect(jsonPath("$.counterpart.accountId").value(clientAccount.toString()))
			.andExpect(jsonPath("$.canWrite").value(true))
			.andExpect(jsonPath("$.isLeader").value(false)));
		perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id));

		send(TRAINER, id, "  Pamiętaj o rozgrzewce bioder.  ").andExpect(status().isCreated())
			.andExpect(jsonPath("$.body").value("Pamiętaj o rozgrzewce bioder."))
			.andExpect(jsonPath("$.isMine").value(true))
			.andExpect(jsonPath("$.senderName").value("Anna Wiśniewska"));

		perform(CLIENT, get(CONVERSATIONS), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].title").value("Anna Wiśniewska"))
			.andExpect(jsonPath("$.items[0].unreadCount").value(1))
			.andExpect(jsonPath("$.items[0].lastMessage.senderName").value("Anna Wiśniewska"))
			.andExpect(jsonPath("$.items[0].lastMessage.body").value("Pamiętaj o rozgrzewce bioder."));
		perform(CLIENT, get(CONVERSATIONS + "/unread-count"), null).andExpect(jsonPath("$.count").value(1));

		send(CLIENT, id, "Dzięki!").andExpect(status().isCreated());
		perform(CLIENT, get(CONVERSATIONS + "/unread-count"), null).andExpect(jsonPath("$.count").value(0));
		perform(TRAINER, get(CONVERSATIONS), null).andExpect(jsonPath("$.items[0].unreadCount").value(1))
			.andExpect(jsonPath("$.items[0].lastMessage.isMine").value(false));
		perform(TRAINER, post(CONVERSATIONS + "/" + id + "/read"), null).andExpect(status().isNoContent());
		perform(TRAINER, get(CONVERSATIONS), null).andExpect(jsonPath("$.items[0].unreadCount").value(0));

		perform(TRAINER, get(CONVERSATIONS + "/" + id + "/messages"), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].body").value(contains("Pamiętaj o rozgrzewce bioder.", "Dzięki!")))
			.andExpect(jsonPath("$.hasMore").value(false));
		perform(OUTSIDER, get(CONVERSATIONS + "/" + id), null).andExpect(status().isNotFound());
		send(OUTSIDER, id, "Hej").andExpect(status().isNotFound());
	}

	@Test
	void aTrainerWritesOnlyToTheirTraineesAndToAChildsGuardian() throws Exception {
		perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}")
			.andExpect(status().isNotFound());
		perform(CLIENT, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}")
			.andExpect(status().isForbidden());

		assignTrainee(child);
		perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + child + "\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.counterpart.accountId").value(parentAccount.toString()))
			.andExpect(jsonPath("$.title").value("Kamil Nowak"));
	}

	@Test
	void aTrainerWritesToParticipantsOfTheirGroupsAndClasses() throws Exception {
		UUID group = insertGroup(TITLE + " stretching");
		UUID groupClass = insertEvent(group, Instant.now().plus(1, ChronoUnit.DAYS));
		assignStaff(groupClass, trainer);
		jdbc.sql("INSERT INTO group_membership (group_id, customer_id) VALUES (:group, :customer)")
			.param("group", group)
			.param("customer", client)
			.update();
		// A class that took place a week ago still counts; one of another trainer does not.
		UUID pastClass = insertEvent(null, Instant.now().minus(7, ChronoUnit.DAYS));
		assignStaff(pastClass, trainer);
		reserve(pastClass, child);
		UUID otherClass = insertEvent(null, Instant.now().plus(1, ChronoUnit.DAYS));
		assignStaff(otherClass, otherTrainer);
		reserve(otherClass, outsider);

		perform(TRAINER, get(CONVERSATIONS + "/clients"), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].displayName").value(contains("Zosia Nowak", "Marta Zielińska")))
			.andExpect(jsonPath("$.items[0].isChild").value(true))
			.andExpect(jsonPath("$.items[0].classes").value(contains(TITLE)))
			.andExpect(jsonPath("$.items[1].isTrainee").value(false))
			.andExpect(jsonPath("$.items[1].classes").value(contains(TITLE + " stretching")))
			.andExpect(jsonPath("$.items[1].conversationId").value(nullValue()));
		perform(CLIENT, get(CONVERSATIONS + "/clients"), null).andExpect(status().isForbidden());

		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("Marta Zielińska")));
		perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + child + "\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.counterpart.accountId").value(parentAccount.toString()));
		perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + outsider + "\"}")
			.andExpect(status().isNotFound());
		perform(TRAINER, get(CONVERSATIONS + "/clients"), null)
			.andExpect(jsonPath("$.items[1].conversationId").value(id));
	}

	@Test
	void aClientWritesToAnyTrainer() throws Exception {
		perform(CLIENT, get(CONVERSATIONS + "/trainers"), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].staffId").value(hasItem(trainer.toString())))
			.andExpect(jsonPath("$.items[*].staffId").value(hasItem(otherTrainer.toString())))
			.andExpect(jsonPath("$.items[*].staffId").value(not(hasItem(receptionist.toString()))));

		String id = idOf(perform(CLIENT, post(CONVERSATIONS + "/trainer"), "{\"staffId\":\"" + otherTrainer + "\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("Tomasz Sikora")));
		send(CLIENT, id, "Czy prowadzi Pan treningi personalne?").andExpect(status().isCreated());
		perform(OTHER_TRAINER, get(CONVERSATIONS), null).andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].title").value("Marta Zielińska"));
		perform(CLIENT, get(CONVERSATIONS + "/trainers"), null)
			.andExpect(jsonPath("$.items[?(@.staffId == '" + otherTrainer + "')].conversationId").value(contains(id)));

		perform(CLIENT, post(CONVERSATIONS + "/trainer"), "{\"staffId\":\"" + receptionist + "\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("STAFF_NOT_TRAINER"));
		perform(TRAINER, post(CONVERSATIONS + "/trainer"), "{\"staffId\":\"" + otherTrainer + "\"}")
			.andExpect(status().isForbidden());
	}

	@Test
	void aClassChatFollowsBookingsAndTheTrainerDecidesWhetherClientsReply() throws Exception {
		UUID event = insertEvent(null, Instant.now().plus(2, ChronoUnit.DAYS));
		assignStaff(event, trainer);
		UUID reservation = reserve(event, client);
		reserve(event, child);

		perform(TRAINER, get(CONVERSATIONS + "/group-chat-targets"), null).andExpect(status().isOk())
			.andExpect(jsonPath("$.events[0].eventId").value(event.toString()))
			.andExpect(jsonPath("$.events[0].participantCount").value(2))
			.andExpect(jsonPath("$.events[0].conversationId").value(nullValue()));
		perform(OTHER_TRAINER, post(CONVERSATIONS + "/group-chats"), eventChatJson(event, false))
			.andExpect(status().isNotFound());
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/group-chats"), eventChatJson(event, false))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("EVENT"))
			.andExpect(jsonPath("$.title").value(TITLE))
			.andExpect(jsonPath("$.isLeader").value(true))
			.andExpect(jsonPath("$.leaders[*].displayName").value(contains("Anna Wiśniewska")))
			.andExpect(jsonPath("$.members[*].displayName").value(contains("Kamil Nowak", "Marta Zielińska"))));
		perform(TRAINER, post(CONVERSATIONS + "/group-chats"), eventChatJson(event, true))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONVERSATION_ALREADY_EXISTS"));

		send(TRAINER, id, "Weźcie wodę i ręcznik.").andExpect(status().isCreated());
		perform(PARENT, get(CONVERSATIONS), null).andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].unreadCount").value(1));
		perform(CLIENT, get(CONVERSATIONS + "/" + id), null).andExpect(jsonPath("$.canWrite").value(false))
			.andExpect(jsonPath("$.isLeader").value(false));
		send(CLIENT, id, "Ok").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CONVERSATION_READ_ONLY"));
		perform(CLIENT, patch(CONVERSATIONS + "/" + id), "{\"clientsCanReply\":true}")
			.andExpect(status().isForbidden());

		perform(TRAINER, patch(CONVERSATIONS + "/" + id), "{\"clientsCanReply\":true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.clientsCanReply").value(true));
		send(CLIENT, id, "Będę 10 min później").andExpect(status().isCreated());
		perform(OUTSIDER, get(CONVERSATIONS + "/" + id), null).andExpect(status().isNotFound());

		// The client cancels the booking: the chat stays with the messages from before.
		jdbc.sql("UPDATE reservation SET status = 'CANCELLED' WHERE id = :id").param("id", reservation).update();
		perform(CLIENT, get(CONVERSATIONS), null).andExpect(jsonPath("$.items[0].hasLeft").value(true));
		send(TRAINER, id, "Zmiana sali na 2.").andExpect(status().isCreated());
		perform(CLIENT, get(CONVERSATIONS + "/" + id), null).andExpect(jsonPath("$.hasLeft").value(true))
			.andExpect(jsonPath("$.canWrite").value(false))
			.andExpect(jsonPath("$.canHide").value(true))
			.andExpect(jsonPath("$.visibleUntil").isNotEmpty());
		perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages"), null)
			.andExpect(jsonPath("$.items[*].body").value(contains("Weźcie wodę i ręcznik.", "Będę 10 min później")));
		perform(PARENT, get(CONVERSATIONS + "/" + id + "/messages"), null)
			.andExpect(jsonPath("$.items.length()").value(3));
		perform(PARENT, delete(CONVERSATIONS + "/" + id), null).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CONVERSATION_STILL_ACTIVE"));

		perform(CLIENT, delete(CONVERSATIONS + "/" + id), null).andExpect(status().isNoContent());
		perform(CLIENT, get(CONVERSATIONS), null).andExpect(jsonPath("$.items").value(empty()));
	}

	@Test
	void aGroupChatHasTheGroupsMembersAndPeopleBookedOnItsClasses() throws Exception {
		UUID group = insertGroup(TITLE + " pole dance wt/czw");
		UUID event = insertEvent(group, Instant.now().plus(1, ChronoUnit.DAYS));
		assignStaff(event, trainer);
		jdbc.sql("INSERT INTO group_membership (group_id, customer_id) VALUES (:group, :customer)")
			.param("group", group)
			.param("customer", client)
			.update();
		reserve(event, child);

		perform(TRAINER, get(CONVERSATIONS + "/group-chat-targets"), null)
			.andExpect(jsonPath("$.groups[0].trainingGroupId").value(group.toString()))
			.andExpect(jsonPath("$.groups[0].memberCount").value(2));
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/group-chats"),
				"{\"trainingGroupId\":\"" + group + "\",\"title\":\"\",\"clientsCanReply\":true}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("GROUP"))
			.andExpect(jsonPath("$.title").value(TITLE + " pole dance wt/czw"))
			.andExpect(jsonPath("$.members.length()").value(2)));

		send(CLIENT, id, "Ktoś ma zapasowe gripy?").andExpect(status().isCreated());
		perform(PARENT, get(CONVERSATIONS + "/" + id + "/messages"), null)
			.andExpect(jsonPath("$.items[0].senderName").value("Marta Zielińska"))
			.andExpect(jsonPath("$.items[0].canDelete").value(false));
		perform(OTHER_TRAINER, get(CONVERSATIONS + "/" + id), null).andExpect(status().isNotFound());
		perform(OTHER_TRAINER, post(CONVERSATIONS + "/group-chats"),
				"{\"trainingGroupId\":\"" + group + "\",\"clientsCanReply\":true}")
			.andExpect(status().isNotFound());
	}

	@Test
	void ownMessagesAndLeadersDeleteMessages() throws Exception {
		UUID event = insertEvent(null, Instant.now().plus(2, ChronoUnit.DAYS));
		assignStaff(event, trainer);
		reserve(event, client);
		reserve(event, child);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/group-chats"), eventChatJson(event, true))
			.andExpect(status().isCreated()));
		String fromTrainer = idOf(send(TRAINER, id, "Witajcie"));
		String fromClient = idOf(send(CLIENT, id, "Cześć"));
		String fromParent = idOf(send(PARENT, id, "Pomyłka"));

		perform(CLIENT, delete(CONVERSATIONS + "/" + id + "/messages/" + fromTrainer), null)
			.andExpect(status().isForbidden());
		perform(PARENT, delete(CONVERSATIONS + "/" + id + "/messages/" + fromParent), null)
			.andExpect(status().isNoContent());
		perform(TRAINER, delete(CONVERSATIONS + "/" + id + "/messages/" + fromClient), null)
			.andExpect(status().isNoContent());

		perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages"), null)
			.andExpect(jsonPath("$.items[*].isDeleted").value(contains(false, true, true)))
			.andExpect(jsonPath("$.items[1].body").value(nullValue()));
		perform(CLIENT, get(CONVERSATIONS), null).andExpect(jsonPath("$.items[0].lastMessage.isDeleted").value(true));
	}

	@Test
	void pollingReportsMessagesDeletedSinceTheLastPoll() throws Exception {
		assignTrainee(client);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}"));
		String first = idOf(send(TRAINER, id, "Jutro o 18?"));
		String second = idOf(send(TRAINER, id, "Pomyłka, o 19"));
		String since = JsonPath.read(perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages"), null)
			.andExpect(jsonPath("$.deletedIds").value(empty()))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.nextDeletedSince");

		perform(TRAINER, delete(CONVERSATIONS + "/" + id + "/messages/" + first), null)
			.andExpect(status().isNoContent());
		perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages").param("after", second).param("deletedSince", since),
				null)
			.andExpect(jsonPath("$.items").value(empty()))
			.andExpect(jsonPath("$.deletedIds").value(contains(first)))
			.andExpect(jsonPath("$.nextDeletedSince").isNotEmpty());
	}

	@Test
	void olderMessagesComePageByPageAndNewOnesAfterTheLastOne() throws Exception {
		assignTrainee(client);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}"));
		String[] ids = new String[5];
		for (int i = 0; i < 5; i++) {
			ids[i] = idOf(send(TRAINER, id, "m" + i));
		}
		perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages?limit=2"), null)
			.andExpect(jsonPath("$.items[*].body").value(contains("m3", "m4")))
			.andExpect(jsonPath("$.hasMore").value(true));
		perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages?limit=2&before=" + ids[3]), null)
			.andExpect(jsonPath("$.items[*].body").value(contains("m1", "m2")))
			.andExpect(jsonPath("$.hasMore").value(true));
		perform(CLIENT, get(CONVERSATIONS + "/" + id + "/messages?limit=10&after=" + ids[2]), null)
			.andExpect(jsonPath("$.items[*].body").value(contains("m3", "m4")))
			.andExpect(jsonPath("$.hasMore").value(false));
	}

	@Test
	void aChatOfAClassThatEndedIsReadOnly() throws Exception {
		UUID event = insertEvent(null, Instant.now().plus(1, ChronoUnit.HOURS));
		assignStaff(event, trainer);
		reserve(event, client);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/group-chats"), eventChatJson(event, true))
			.andExpect(status().isCreated()));
		jdbc.sql("UPDATE schedule_event SET starts_at = now() - interval '2 hours', ends_at = now() - interval '1 hour' "
				+ "WHERE id = :id")
			.param("id", event)
			.update();

		perform(TRAINER, get(CONVERSATIONS), null).andExpect(jsonPath("$.items[0].isClosed").value(true));
		perform(TRAINER, get(CONVERSATIONS + "/" + id), null).andExpect(jsonPath("$.canWrite").value(false));
		send(TRAINER, id, "Dzięki za dziś").andExpect(status().isUnprocessableContent());
		perform(CLIENT, get(CONVERSATIONS + "/" + id), null).andExpect(jsonPath("$.hasLeft").value(false));
	}

	@Test
	void conversationsAreForClientsAndTrainers() throws Exception {
		perform(ADMIN, get(CONVERSATIONS), null).andExpect(status().isForbidden());
		perform(RECEPTION, get(CONVERSATIONS), null).andExpect(status().isForbidden());
		mvc.perform(get(CONVERSATIONS)).andExpect(status().isUnauthorized());
		perform(CLIENT, get(CONVERSATIONS + "/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
		perform(CLIENT, get(CONVERSATIONS + "/group-chat-targets"), null).andExpect(status().isForbidden());
	}

	@Test
	void aNewMessageNotifiesTheOtherPersonInTheRoleItConcerns() throws Exception {
		assignTrainee(client);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}"));

		String fromTrainer = idOf(send(TRAINER, id, "Pamiętaj o rozgrzewce bioder."));
		await(() -> notifications(clientAccount).size() == 1);
		assertThat(notifications(clientAccount)).containsExactly(
				"CLIENT|Anna Wiśniewska|Pamiętaj o rozgrzewce bioder.|" + id + "|" + fromTrainer + "|CLIENT");

		send(CLIENT, id, "Dzięki! " + "a".repeat(120)).andExpect(status().isCreated());
		await(() -> notifications(trainerAccount).size() == 1);
		assertThat(notifications(trainerAccount)).singleElement()
			.asString()
			.startsWith("TRAINER|Marta Zielińska|Dzięki! " + "a".repeat(91) + "…|" + id + "|");

		send(TRAINER, id, "Do jutra").andExpect(status().isCreated());
		await(() -> notifications(clientAccount).size() == 2);
		assertThat(notifications(trainerAccount)).hasSize(1);
	}

	@Test
	void aMessageInAClassChatNotifiesItsLeadersAndMembersButNotTheSender() throws Exception {
		UUID event = insertEvent(null, Instant.now().plus(2, ChronoUnit.DAYS));
		assignStaff(event, trainer);
		reserve(event, client);
		reserve(event, child);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/group-chats"), eventChatJson(event, true)));

		send(CLIENT, id, "Będę 10 min później").andExpect(status().isCreated());
		await(() -> notifications(trainerAccount).size() == 1 && notifications(parentAccount).size() == 1);
		assertThat(notifications(trainerAccount)).singleElement()
			.asString()
			.startsWith("TRAINER|" + TITLE + " · Marta Zielińska|Będę 10 min później|" + id + "|");
		assertThat(notifications(parentAccount)).singleElement()
			.asString()
			.startsWith("CLIENT|" + TITLE + " · Marta Zielińska|");
		assertThat(notifications(clientAccount)).isEmpty();
	}

	@Test
	void noMessageNotificationsWhileTheTypeIsSwitchedOff() throws Exception {
		assignTrainee(client);
		String id = idOf(perform(TRAINER, post(CONVERSATIONS + "/trainee"), "{\"customerId\":\"" + client + "\"}"));
		jdbc.sql("UPDATE notification_type SET is_enabled = FALSE WHERE code = 'NEW_MESSAGE'").update();
		try {
			send(TRAINER, id, "Jutro o 18?").andExpect(status().isCreated());
			NotificationCleanup.deleteNotifications(jdbc, "SELECT CAST('" + trainerAccount + "' AS UUID)");
			assertThat(notifications(clientAccount)).isEmpty();
		}
		finally {
			jdbc.sql("UPDATE notification_type SET is_enabled = TRUE WHERE code = 'NEW_MESSAGE'").update();
		}
	}

	private ResultActions send(String token, String conversationId, String body) throws Exception {
		return perform(token, post(CONVERSATIONS + "/" + conversationId + "/messages"),
				"{\"body\":\"" + body + "\"}");
	}

	/** The account's message notifications as role|title|body|conversation|message|role in the data. */
	private List<String> notifications(UUID account) {
		return jdbc.sql("""
				SELECT n.recipient_role || '|' || n.title || '|' || n.body || '|' || (n.payload ->> 'conversationId')
				    || '|' || (n.payload ->> 'messageId') || '|' || (n.payload ->> 'role')
				FROM notification n
				JOIN notification_type t ON t.id = n.notification_type_id
				WHERE t.code = 'NEW_MESSAGE' AND n.recipient_id = :account
				ORDER BY n.created_at
				""")
			.param("account", account)
			.query(String.class)
			.list();
	}

	/** Notifications are created in the background right after the commit. */
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
	}

	private static String eventChatJson(UUID event, boolean clientsCanReply) {
		return "{\"eventId\":\"" + event + "\",\"clientsCanReply\":" + clientsCanReply + "}";
	}

	private static String idOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions perform(String token, MockHttpServletRequestBuilder request, String json) throws Exception {
		request.header("Authorization", "Bearer " + token);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return mvc.perform(request);
	}

	private UUID addAccount(String name, String roleCode, String token) {
		UUID id = jdbc.sql("INSERT INTO account (email, status) VALUES (:email, 'ACTIVE') RETURNING id")
			.param("email", name + EMAIL_DOMAIN)
			.query(UUID.class)
			.single();
		String uid = "chat-" + id;
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

	private UUID insertCustomer(UUID accountId, String kind, String firstName, String lastName) {
		return jdbc.sql("""
				INSERT INTO customer_profile (account_id, kind, first_name, last_name, birth_date)
				VALUES (:accountId, :kind, :firstName, :lastName, :birthDate)
				RETURNING id
				""")
			.param("accountId", accountId)
			.param("kind", kind)
			.param("firstName", firstName)
			.param("lastName", lastName)
			.param("birthDate", kind.equals("CHILD") ? LocalDate.of(2016, 3, 1) : LocalDate.of(1992, 6, 1))
			.query(UUID.class)
			.single();
	}

	private void assignTrainee(UUID customerId) {
		jdbc.sql("""
				INSERT INTO staff_assignment (customer_id, staff_id, assigned_by)
				VALUES (:customer, :staff, :assignedBy)
				""")
			.param("customer", customerId)
			.param("staff", trainer)
			.param("assignedBy", trainerAccount)
			.update();
	}

	private UUID insertGroup(String name) {
		return jdbc.sql("INSERT INTO training_group (name, service_type_id) VALUES (:name, :serviceType) RETURNING id")
			.param("name", name)
			.param("serviceType", serviceType)
			.query(UUID.class)
			.single();
	}

	private UUID insertEvent(UUID groupId, Instant startsAt) {
		return jdbc.sql("""
				INSERT INTO schedule_event (kind, service_type_id, training_group_id, title, starts_at, ends_at, capacity)
				VALUES ('CLASS', :serviceType, :groupId, :title, :startsAt, :endsAt, 12)
				RETURNING id
				""")
			.param("serviceType", serviceType)
			.param("groupId", groupId)
			.param("title", TITLE)
			.param("startsAt", startsAt.atOffset(ZoneOffset.UTC))
			.param("endsAt", startsAt.plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
			.query(UUID.class)
			.single();
	}

	private void assignStaff(UUID eventId, UUID staffId) {
		jdbc.sql("INSERT INTO event_staff (event_id, staff_id) VALUES (:event, :staff)")
			.param("event", eventId)
			.param("staff", staffId)
			.update();
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
