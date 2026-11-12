package studioforyou.pl.backend.communication.messaging;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Conversations, their participants and messages.
 */
@Repository
class ConversationStore {

	private final JdbcClient jdbc;

	ConversationStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Conversation> find(UUID id) {
		return jdbc.sql("""
				SELECT id, kind, training_group_id, event_id, title, created_by, created_at, clients_can_reply
				FROM conversation
				WHERE id = :id
				""")
			.param("id", id)
			.query(Conversation.class)
			.optional();
	}

	/** Group and class chats of the given groups and events, and the ones the account created. */
	List<Conversation> findGroupChats(Collection<UUID> groupIds, Collection<UUID> eventIds, UUID createdBy) {
		return jdbc.sql("""
				SELECT id, kind, training_group_id, event_id, title, created_by, created_at, clients_can_reply
				FROM conversation
				WHERE (kind = 'GROUP' AND (training_group_id IN (:groupIds) OR created_by = :createdBy))
				   OR (kind = 'EVENT' AND (event_id IN (:eventIds) OR created_by = :createdBy))
				""")
			// IN () is not valid SQL, so an empty set becomes a value no row has.
			.param("groupIds", orNone(groupIds))
			.param("eventIds", orNone(eventIds))
			.param("createdBy", createdBy)
			.query(Conversation.class)
			.list();
	}

	/** The chats of the given groups and events, whoever created them. */
	List<Conversation> findByTargets(Collection<UUID> groupIds, Collection<UUID> eventIds) {
		return jdbc.sql("""
				SELECT id, kind, training_group_id, event_id, title, created_by, created_at, clients_can_reply
				FROM conversation
				WHERE (kind = 'GROUP' AND training_group_id IN (:groupIds))
				   OR (kind = 'EVENT' AND event_id IN (:eventIds))
				""")
			.param("groupIds", orNone(groupIds))
			.param("eventIds", orNone(eventIds))
			.query(Conversation.class)
			.list();
	}

	/** The 1:1 conversation of the two accounts, if they have one. */
	Optional<UUID> findDirect(UUID accountId, UUID otherAccountId) {
		return jdbc.sql("""
				SELECT c.id
				FROM conversation c
				JOIN conversation_participant a ON a.conversation_id = c.id AND a.account_id = :accountId
				JOIN conversation_participant b ON b.conversation_id = c.id AND b.account_id = :otherAccountId
				WHERE c.kind = 'DIRECT'
				ORDER BY c.created_at, c.id
				LIMIT 1
				""")
			.param("accountId", accountId)
			.param("otherAccountId", otherAccountId)
			.query(UUID.class)
			.optional();
	}

	/**
	 * Holds a transaction-scoped lock for the pair of accounts, so two requests cannot create two
	 * 1:1 conversations for the same people.
	 */
	void lockPair(UUID accountId, UUID otherAccountId) {
		String key = (accountId.compareTo(otherAccountId) < 0) ? accountId + ":" + otherAccountId
				: otherAccountId + ":" + accountId;
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query().singleRow();
	}

	UUID createDirect(UUID creatorId, UUID otherAccountId) {
		UUID id = jdbc.sql("""
				INSERT INTO conversation (kind, created_by)
				VALUES ('DIRECT', :createdBy)
				RETURNING id
				""")
			.param("createdBy", creatorId)
			.query(UUID.class)
			.single();
		join(id, creatorId);
		join(id, otherAccountId);
		return id;
	}

	UUID createGroupChat(ConversationKind kind, @Nullable UUID groupId, @Nullable UUID eventId, String title,
			boolean clientsCanReply, UUID creatorId) {
		try {
			return jdbc.sql("""
					INSERT INTO conversation (kind, training_group_id, event_id, title, created_by, clients_can_reply)
					VALUES (:kind, :groupId, :eventId, :title, :createdBy, :clientsCanReply)
					RETURNING id
					""")
				.param("kind", kind.name())
				.param("groupId", groupId)
				.param("eventId", eventId)
				.param("title", title)
				.param("createdBy", creatorId)
				.param("clientsCanReply", clientsCanReply)
				.query(UUID.class)
				.single();
		}
		catch (DuplicateKeyException ex) {
			// Another trainer created the chat at the same moment.
			throw alreadyExists();
		}
	}

	static ApiException alreadyExists() {
		return new ApiException(ErrorCode.CONVERSATION_ALREADY_EXISTS, "This group or class already has a chat.");
	}

	void setClientsCanReply(UUID conversationId, boolean clientsCanReply) {
		jdbc.sql("UPDATE conversation SET clients_can_reply = :value WHERE id = :id")
			.param("id", conversationId)
			.param("value", clientsCanReply)
			.update();
	}

	Optional<Participant> findParticipant(UUID conversationId, UUID accountId) {
		return jdbc.sql("""
				SELECT joined_at, left_at, last_read_at, hidden_at
				FROM conversation_participant
				WHERE conversation_id = :conversationId AND account_id = :accountId
				""")
			.param("conversationId", conversationId)
			.param("accountId", accountId)
			.query(Participant.class)
			.optional();
	}

	/** Adds the account to the conversation, or brings back an account that had left it. */
	void join(UUID conversationId, UUID accountId) {
		jdbc.sql("""
				INSERT INTO conversation_participant (conversation_id, account_id)
				VALUES (:conversationId, :accountId)
				ON CONFLICT (conversation_id, account_id)
				DO UPDATE SET left_at = NULL, hidden_at = NULL
				WHERE conversation_participant.left_at IS NOT NULL
				""")
			.param("conversationId", conversationId)
			.param("accountId", accountId)
			.update();
	}

	/** Marks the account as having left the group and class chats it is in, except the given ones. */
	void leaveGroupChatsExcept(UUID accountId, Collection<UUID> keptConversationIds) {
		jdbc.sql("""
				UPDATE conversation_participant p
				SET left_at = now()
				FROM conversation c
				WHERE c.id = p.conversation_id AND c.kind <> 'DIRECT' AND p.account_id = :accountId
				  AND p.left_at IS NULL AND p.conversation_id NOT IN (:kept)
				""")
			.param("accountId", accountId)
			.param("kept", orNone(keptConversationIds))
			.update();
	}

	/** Marks the account as having left the conversation, if it has not left it yet. */
	void leave(UUID conversationId, UUID accountId) {
		jdbc.sql("""
				UPDATE conversation_participant
				SET left_at = now()
				WHERE conversation_id = :conversationId AND account_id = :accountId AND left_at IS NULL
				""")
			.param("conversationId", conversationId)
			.param("accountId", accountId)
			.update();
	}

	/** The other participant of each of the given 1:1 conversations of the account. */
	Map<UUID, UUID> findCounterparts(UUID accountId, Collection<UUID> conversationIds) {
		if (conversationIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, UUID> counterparts = new HashMap<>();
		jdbc.sql("""
				SELECT conversation_id, account_id
				FROM conversation_participant
				WHERE conversation_id IN (:ids) AND account_id <> :accountId
				ORDER BY joined_at
				""")
			.param("ids", conversationIds)
			.param("accountId", accountId)
			.query((rs) -> {
				counterparts.putIfAbsent(rs.getObject("conversation_id", UUID.class),
						rs.getObject("account_id", UUID.class));
			});
		return counterparts;
	}

	void markRead(UUID conversationId, UUID accountId) {
		jdbc.sql("""
				UPDATE conversation_participant
				SET last_read_at = now()
				WHERE conversation_id = :conversationId AND account_id = :accountId
				""")
			.param("conversationId", conversationId)
			.param("accountId", accountId)
			.update();
	}

	/**
	 * The messages of the conversation the account has not read yet, oldest first: written by
	 * others, not deleted, after the account last read the conversation and before it left.
	 */
	List<Message> findUnread(UUID conversationId, UUID accountId) {
		return jdbc.sql("""
				SELECT m.id, m.conversation_id, m.sender_id, m.body, m.created_at, m.deleted_at
				FROM message m
				LEFT JOIN conversation_participant p
				  ON p.conversation_id = m.conversation_id AND p.account_id = :accountId
				WHERE m.conversation_id = :conversationId AND m.deleted_at IS NULL AND m.sender_id <> :accountId
				  AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)
				  AND (p.left_at IS NULL OR m.created_at <= p.left_at)
				ORDER BY m.created_at, m.id
				""")
			.param("conversationId", conversationId)
			.param("accountId", accountId)
			.query(Message.class)
			.list();
	}

	void hide(UUID conversationId, UUID accountId) {
		jdbc.sql("""
				UPDATE conversation_participant
				SET hidden_at = now()
				WHERE conversation_id = :conversationId AND account_id = :accountId
				""")
			.param("conversationId", conversationId)
			.param("accountId", accountId)
			.update();
	}

	/**
	 * The account's conversations with their latest message and the number of unread messages,
	 * most recently active first. A participant who left sees only messages from before they left;
	 * a hidden conversation comes back with a newer message.
	 */
	List<Summary> listFor(UUID accountId) {
		return jdbc.sql("""
				SELECT c.id, c.kind, c.training_group_id, c.event_id, c.title, c.created_by, c.created_at,
				       c.clients_can_reply, p.left_at,
				       lm.id AS last_message_id, lm.sender_id AS last_sender_id, lm.body AS last_body,
				       lm.created_at AS last_created_at, lm.deleted_at IS NOT NULL AS last_deleted,
				       (SELECT count(*)
				        FROM message m
				        WHERE m.conversation_id = c.id AND m.deleted_at IS NULL AND m.sender_id <> :accountId
				          AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)
				          AND (p.left_at IS NULL OR m.created_at <= p.left_at)) AS unread_count
				FROM conversation_participant p
				JOIN conversation c ON c.id = p.conversation_id
				LEFT JOIN LATERAL (
				    SELECT m.id, m.sender_id, m.body, m.created_at, m.deleted_at
				    FROM message m
				    WHERE m.conversation_id = c.id AND (p.left_at IS NULL OR m.created_at <= p.left_at)
				    ORDER BY m.created_at DESC, m.id DESC
				    LIMIT 1
				) lm ON TRUE
				WHERE p.account_id = :accountId AND (p.hidden_at IS NULL OR lm.created_at > p.hidden_at)
				ORDER BY COALESCE(lm.created_at, c.created_at) DESC, c.id
				""")
			.param("accountId", accountId)
			.query(Summary.class)
			.list();
	}

	/** Unread messages in all the account's conversations it has not hidden. */
	long countUnread(UUID accountId) {
		return jdbc.sql("""
				SELECT count(*)
				FROM conversation_participant p
				JOIN message m ON m.conversation_id = p.conversation_id
				WHERE p.account_id = :accountId AND m.deleted_at IS NULL AND m.sender_id <> :accountId
				  AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)
				  AND (p.left_at IS NULL OR m.created_at <= p.left_at)
				  AND (p.hidden_at IS NULL OR m.created_at > p.hidden_at)
				""")
			.param("accountId", accountId)
			.query(Long.class)
			.single();
	}

	/**
	 * Up to {@code limit} messages, oldest first: the latest ones, the ones just before
	 * {@code before} or the ones just after {@code after}. Messages after {@code upTo} are left out.
	 */
	List<Message> listMessages(UUID conversationId, @Nullable Instant upTo, @Nullable Message before,
			@Nullable Message after, int limit) {
		String where = "conversation_id = :conversationId";
		if (upTo != null) {
			where += " AND created_at <= :upTo";
		}
		if (before != null) {
			where += " AND (created_at, id) < (:cursorAt, :cursorId)";
		}
		if (after != null) {
			where += " AND (created_at, id) > (:cursorAt, :cursorId)";
		}
		String order = (after != null) ? "ASC" : "DESC";
		var spec = jdbc.sql("""
				SELECT id, conversation_id, sender_id, body, created_at, deleted_at
				FROM message
				WHERE %s
				ORDER BY created_at %s, id %s
				LIMIT :limit
				""".formatted(where, order, order))
			.param("conversationId", conversationId)
			.param("limit", limit);
		if (upTo != null) {
			spec = spec.param("upTo", utc(upTo));
		}
		Message cursor = (before != null) ? before : after;
		if (cursor != null) {
			spec = spec.param("cursorAt", cursor.createdAt()).param("cursorId", cursor.id());
		}
		List<Message> messages = new ArrayList<>(spec.query(Message.class).list());
		if (after == null) {
			Collections.reverse(messages);
		}
		return messages;
	}

	Optional<Message> findMessage(UUID conversationId, UUID messageId) {
		return jdbc.sql("""
				SELECT id, conversation_id, sender_id, body, created_at, deleted_at
				FROM message
				WHERE id = :id AND conversation_id = :conversationId
				""")
			.param("id", messageId)
			.param("conversationId", conversationId)
			.query(Message.class)
			.optional();
	}

	Message insertMessage(UUID conversationId, UUID senderId, String body) {
		return jdbc.sql("""
				INSERT INTO message (conversation_id, sender_id, body)
				VALUES (:conversationId, :senderId, :body)
				RETURNING id, conversation_id, sender_id, body, created_at, deleted_at
				""")
			.param("conversationId", conversationId)
			.param("senderId", senderId)
			.param("body", body)
			.query(Message.class)
			.single();
	}

	/** Removes the text of the message; the message stays in its place as a deleted one. */
	void deleteMessage(UUID messageId) {
		jdbc.sql("UPDATE message SET body = NULL, deleted_at = now() WHERE id = :id AND deleted_at IS NULL")
			.param("id", messageId)
			.update();
	}

	/** Messages deleted at or after {@code since}; messages after {@code upTo} are left out. */
	List<UUID> findDeletedSince(UUID conversationId, Instant since, @Nullable Instant upTo) {
		var spec = jdbc.sql("""
				SELECT id
				FROM message
				WHERE conversation_id = :conversationId AND deleted_at >= :since%s
				""".formatted((upTo != null) ? " AND created_at <= :upTo" : ""))
			.param("conversationId", conversationId)
			.param("since", utc(since));
		if (upTo != null) {
			spec = spec.param("upTo", utc(upTo));
		}
		return spec.query(UUID.class).list();
	}

	/**
	 * Where the next look for deleted messages starts: the database's clock a little in the past, so
	 * deletions committed while this request runs are not missed.
	 */
	Instant deletionCursor() {
		return jdbc.sql("SELECT now() - INTERVAL '1 minute'").query(OffsetDateTime.class).single().toInstant();
	}

	private static Collection<UUID> orNone(Collection<UUID> ids) {
		return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	record Conversation(UUID id, ConversationKind kind, @Nullable UUID trainingGroupId, @Nullable UUID eventId,
			@Nullable String title, UUID createdBy, OffsetDateTime createdAt, boolean clientsCanReply) {
	}

	record Participant(OffsetDateTime joinedAt, @Nullable OffsetDateTime leftAt, @Nullable OffsetDateTime lastReadAt,
			@Nullable OffsetDateTime hiddenAt) {
	}

	record Message(UUID id, UUID conversationId, UUID senderId, @Nullable String body, OffsetDateTime createdAt,
			@Nullable OffsetDateTime deletedAt) {
	}

	record Summary(UUID id, ConversationKind kind, @Nullable UUID trainingGroupId, @Nullable UUID eventId,
			@Nullable String title, UUID createdBy, OffsetDateTime createdAt, boolean clientsCanReply,
			@Nullable OffsetDateTime leftAt, @Nullable UUID lastMessageId, @Nullable UUID lastSenderId,
			@Nullable String lastBody, @Nullable OffsetDateTime lastCreatedAt, @Nullable Boolean lastDeleted,
			long unreadCount) {

		Conversation conversation() {
			return new Conversation(id, kind, trainingGroupId, eventId, title, createdBy, createdAt, clientsCanReply);
		}

	}

}
