package studioforyou.pl.backend.communication.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Notification settings, notifications and their delivery attempts ({@code notification_type},
 * {@code notification_lead_time}, {@code notification_template}, {@code notification},
 * {@code notification_delivery}).
 */
@Repository
public class NotificationStore {

	/** The only channel and template language for now: push in Polish. */
	static final String PUSH = "PUSH";

	static final String LOCALE = "pl";

	private static final TypeReference<Map<String, String>> DATA = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	NotificationStore(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	// --- settings ---

	/** The administrator's settings of the type, with its push template. */
	public TypeSettings settings(NotificationType type) {
		record Row(boolean isEnabled, OffsetDateTime updatedAt, String subject, String bodyTemplate) {
		}
		Row row = jdbc.sql("""
				SELECT t.is_enabled, t.updated_at, tpl.subject, tpl.body_template
				FROM notification_type t
				LEFT JOIN notification_template tpl ON tpl.notification_type_id = t.id
				  AND tpl.channel = :channel AND tpl.locale = :locale
				WHERE t.id = :id
				""")
			.param("id", type.id())
			.param("channel", PUSH)
			.param("locale", LOCALE)
			.query(Row.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("Notification type " + type + " is missing in the database"));
		return new TypeSettings(type, row.isEnabled(), type.timed() ? leadTimes(type) : List.of(),
				row.subject() == null ? "" : row.subject(), row.bodyTemplate() == null ? "" : row.bodyTemplate(),
				row.updatedAt().toInstant());
	}

	/** Reminder times of a timed type, in minutes before the start, longest first. */
	public List<Integer> leadTimes(NotificationType type) {
		return jdbc.sql("""
				SELECT minutes_before FROM notification_lead_time
				WHERE notification_type_id = :id
				ORDER BY minutes_before DESC
				""")
			.param("id", type.id())
			.query(Integer.class)
			.list();
	}

	/** Replaces the type's settings; the caller validates them. */
	@Transactional
	public void updateSettings(NotificationType type, boolean enabled, Collection<Integer> leadTimes, String subject,
			String body, UUID updatedBy) {
		jdbc.sql("""
				UPDATE notification_type SET is_enabled = :enabled, updated_by = :updatedBy, updated_at = now()
				WHERE id = :id
				""")
			.param("id", type.id())
			.param("enabled", enabled)
			.param("updatedBy", updatedBy)
			.update();
		if (type.timed()) {
			jdbc.sql("DELETE FROM notification_lead_time WHERE notification_type_id = :id").param("id", type.id()).update();
			for (Integer minutes : leadTimes) {
				jdbc.sql("""
						INSERT INTO notification_lead_time (notification_type_id, minutes_before)
						VALUES (:id, :minutes)
						""")
					.param("id", type.id())
					.param("minutes", minutes)
					.update();
			}
		}
		jdbc.sql("""
				INSERT INTO notification_template (notification_type_id, channel, locale, subject, body_template, updated_by)
				VALUES (:id, :channel, :locale, :subject, :body, :updatedBy)
				ON CONFLICT (notification_type_id, channel, locale) DO UPDATE
				SET subject = EXCLUDED.subject, body_template = EXCLUDED.body_template,
				    updated_by = EXCLUDED.updated_by, updated_at = now()
				""")
			.param("id", type.id())
			.param("channel", PUSH)
			.param("locale", LOCALE)
			.param("subject", subject)
			.param("body", body)
			.param("updatedBy", updatedBy)
			.update();
	}

	// --- notifications ---

	/**
	 * Stores the notification unless one with the same deduplication key exists.
	 *
	 * @return the new notification's ID, empty for a duplicate
	 */
	public Optional<UUID> insert(NotificationRequest request, String title, String body) {
		return jdbc.sql("""
				INSERT INTO notification (recipient_id, notification_type_id, recipient_role, subject_customer_id,
				  title, body, payload, dedup_key, scheduled_for)
				VALUES (:recipient, :type, :role, :subject, :title, :body, CAST(:payload AS JSONB), :dedupKey, :scheduledFor)
				ON CONFLICT (dedup_key) DO NOTHING
				RETURNING id
				""")
			.param("recipient", request.recipientId())
			.param("type", request.type().id())
			.param("role", request.role().name())
			.param("subject", request.subjectCustomerId(), java.sql.Types.OTHER)
			.param("title", title)
			.param("body", body)
			.param("payload", json.writeValueAsString(request.data()))
			.param("dedupKey", request.dedupKey())
			.param("scheduledFor", utc(request.scheduledFor()))
			.query(UUID.class)
			.optional();
	}

	/** The notifications to send, by ID. */
	public List<Outgoing> findOutgoing(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT n.id, n.recipient_id, t.code, n.title, n.body, n.payload::text AS payload
				FROM notification n
				JOIN notification_type t ON t.id = n.notification_type_id
				WHERE n.id IN (:ids)
				""")
			.param("ids", ids)
			.query((rs, rowNum) -> new Outgoing(rs.getObject("id", UUID.class), rs.getObject("recipient_id", UUID.class),
					rs.getString("code"), rs.getString("title"), rs.getString("body"), data(rs.getString("payload"))))
			.list();
	}

	public void recordDelivery(UUID notificationId, DeliveryStatus status, String providerMessageId, String error) {
		jdbc.sql("""
				INSERT INTO notification_delivery (notification_id, channel, status, provider_message_id, error)
				VALUES (:id, :channel, :status, :messageId, :error)
				""")
			.param("id", notificationId)
			.param("channel", PUSH)
			.param("status", status.name())
			.param("messageId", providerMessageId)
			.param("error", error == null ? null : error.substring(0, Math.min(error.length(), 2000)))
			.update();
	}

	/**
	 * Due notifications created in {@code [since, createdBefore)} that were neither delivered nor
	 * found without a device, and failed fewer than {@code maxFailures} times.
	 */
	public List<UUID> findUndelivered(Instant now, Instant since, Instant createdBefore, int maxFailures) {
		return jdbc.sql("""
				SELECT n.id
				FROM notification n
				WHERE n.scheduled_for <= :now AND n.created_at >= :since AND n.created_at < :createdBefore
				  AND NOT EXISTS (SELECT 1 FROM notification_delivery d
				                  WHERE d.notification_id = n.id AND d.status IN ('SENT', 'NO_DEVICE'))
				  AND (SELECT count(*) FROM notification_delivery d
				       WHERE d.notification_id = n.id AND d.status = 'FAILED') < :maxFailures
				ORDER BY n.created_at
				LIMIT 500
				""")
			.param("now", utc(now))
			.param("since", utc(since))
			.param("createdBefore", utc(createdBefore))
			.param("maxFailures", maxFailures)
			.query(UUID.class)
			.list();
	}

	// --- the recipient's inbox ---

	/** The account's due notifications, newest first; {@code role} narrows them to one role (and older rows without one). */
	public PageResponse<InboxItem> inbox(UUID accountId, NotificationRole role, Instant now, int page, int size) {
		long total = jdbc.sql("SELECT count(*) FROM notification n WHERE " + INBOX_WHERE)
			.param("accountId", accountId)
			.param("role", role == null ? null : role.name())
			.param("now", utc(now))
			.query(Long.class)
			.single();
		List<InboxItem> items = jdbc.sql(INBOX_COLUMNS + " WHERE " + INBOX_WHERE + """

				ORDER BY n.scheduled_for DESC, n.id
				LIMIT :limit OFFSET :offset
				""")
			.param("accountId", accountId)
			.param("role", role == null ? null : role.name())
			.param("now", utc(now))
			.param("limit", size)
			.param("offset", (long) page * size)
			.query(inboxItem())
			.list();
		return PageResponse.of(items, page, size, total);
	}

	public long unreadCount(UUID accountId, NotificationRole role, Instant now) {
		return jdbc.sql("SELECT count(*) FROM notification n WHERE " + INBOX_WHERE + " AND n.read_at IS NULL")
			.param("accountId", accountId)
			.param("role", role == null ? null : role.name())
			.param("now", utc(now))
			.query(Long.class)
			.single();
	}

	/** Marks the account's notification read (keeping the first read time). */
	public Optional<InboxItem> markRead(UUID accountId, UUID notificationId, Instant now) {
		int updated = jdbc.sql("""
				UPDATE notification SET read_at = COALESCE(read_at, now())
				WHERE id = :id AND recipient_id = :accountId AND scheduled_for <= :now
				""")
			.param("id", notificationId)
			.param("accountId", accountId)
			.param("now", utc(now))
			.update();
		if (updated == 0) {
			return Optional.empty();
		}
		return jdbc.sql(INBOX_COLUMNS + " WHERE n.id = :id").param("id", notificationId).query(inboxItem()).optional();
	}

	/** Marks all the account's due unread notifications (of one role, if given) read; returns how many. */
	public int markAllRead(UUID accountId, NotificationRole role, Instant now) {
		return jdbc.sql("UPDATE notification n SET read_at = now() WHERE " + INBOX_WHERE + " AND n.read_at IS NULL")
			.param("accountId", accountId)
			.param("role", role == null ? null : role.name())
			.param("now", utc(now))
			.update();
	}

	private static final String INBOX_WHERE = """
			n.recipient_id = :accountId AND n.scheduled_for <= :now
			  AND (CAST(:role AS VARCHAR) IS NULL OR n.recipient_role = :role OR n.recipient_role IS NULL)""";

	private static final String INBOX_COLUMNS = """
			SELECT n.id, t.code, n.recipient_role, n.subject_customer_id, n.title, n.body, n.payload::text AS payload,
			       n.scheduled_for, n.read_at
			FROM notification n
			JOIN notification_type t ON t.id = n.notification_type_id""";

	private RowMapper<InboxItem> inboxItem() {
		return (rs, rowNum) -> new InboxItem(rs.getObject("id", UUID.class), rs.getString("code"),
				rs.getString("recipient_role"), rs.getObject("subject_customer_id", UUID.class), rs.getString("title"),
				rs.getString("body"), data(rs.getString("payload")), instant(rs, "scheduled_for"), instant(rs, "read_at"));
	}

	private Map<String, String> data(String payload) {
		if (payload == null || payload.isBlank()) {
			return Map.of();
		}
		try {
			Map<String, String> values = json.readValue(payload, DATA);
			return values == null ? Map.of() : values;
		}
		catch (RuntimeException ex) {
			// Older rows may hold non-string values; the app gets no data for them rather than an error.
			return Map.of();
		}
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	/** Status of a delivery attempt ({@code notification_delivery.status}). */
	public enum DeliveryStatus {

		/** Accepted by the push provider for one device. */
		SENT,

		/** The provider could not send it; tried again later. */
		FAILED,

		/** The device's token is no longer valid and was revoked. */
		TOKEN_INVALID,

		/** The recipient has no registered device; the notification stays in the in-app inbox only. */
		NO_DEVICE

	}

	/**
	 * @param leadTimes minutes before the start, longest first; empty for a type that is not timed
	 * @param subject the push title template
	 * @param body the push text template
	 */
	public record TypeSettings(NotificationType type, boolean enabled, List<Integer> leadTimes, String subject,
			String body, Instant updatedAt) {
	}

	/** A stored notification ready to send. */
	public record Outgoing(UUID id, UUID recipientId, String typeCode, String title, String body,
			Map<String, String> data) {
	}

	/** A notification as the recipient sees it. */
	public record InboxItem(UUID id, String typeCode, String role, UUID subjectCustomerId, String title, String body,
			Map<String, String> data, Instant createdAt, Instant readAt) {
	}

}
