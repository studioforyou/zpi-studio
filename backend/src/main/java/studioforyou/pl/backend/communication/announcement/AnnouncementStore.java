package studioforyou.pl.backend.communication.announcement;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.communication.notification.NotificationRole;
import studioforyou.pl.backend.communication.notification.NotificationType;
import studioforyou.pl.backend.shared.api.PageResponse;

/** Announcements and the notifications that deliver them ({@code announcement}, {@code notification}). */
@Repository
class AnnouncementStore {

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	AnnouncementStore(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	UUID insert(NewAnnouncement announcement) {
		return jdbc.sql("""
				INSERT INTO announcement (author_id, audience, target_account_id, training_group_id, event_id,
				  is_marketing, title, body, sent_at, skipped_without_consent)
				VALUES (:author, :audience, :account, :group, :event, :marketing, :title, :body, :sentAt, :skipped)
				RETURNING id
				""")
			.param("author", announcement.authorId())
			.param("audience", announcement.audience().name())
			.param("account", announcement.accountId(), java.sql.Types.OTHER)
			.param("group", announcement.trainingGroupId(), java.sql.Types.OTHER)
			.param("event", announcement.eventId(), java.sql.Types.OTHER)
			.param("marketing", announcement.marketing())
			.param("title", announcement.title())
			.param("body", announcement.body())
			.param("sentAt", utc(announcement.sentAt()))
			.param("skipped", announcement.skippedWithoutConsent())
			.query(UUID.class)
			.single();
	}

	/** Stores the inbox notification of one recipient; returns its ID. */
	UUID insertNotification(UUID announcementId, boolean marketing, UUID recipientId, @Nullable NotificationRole role,
			@Nullable UUID subjectCustomerId, String title, String body, Map<String, String> data, Instant sentAt) {
		return jdbc.sql("""
				INSERT INTO notification (recipient_id, notification_type_id, recipient_role, subject_customer_id,
				  category, title, body, payload, announcement_id, dedup_key, scheduled_for, created_at)
				VALUES (:recipient, :type, :role, :subject, :category, :title, :body, CAST(:payload AS JSONB),
				  :announcement, :dedupKey, :sentAt, :sentAt)
				RETURNING id
				""")
			.param("recipient", recipientId)
			.param("type", NotificationType.ANNOUNCEMENT.id())
			.param("role", role == null ? null : role.name(), java.sql.Types.VARCHAR)
			.param("subject", subjectCustomerId, java.sql.Types.OTHER)
			.param("category", marketing ? "MARKETING" : "TRANSACTIONAL")
			.param("title", title)
			.param("body", body)
			.param("payload", json.writeValueAsString(data))
			.param("announcement", announcementId)
			.param("dedupKey", NotificationType.ANNOUNCEMENT.name() + ":" + announcementId + ":" + recipientId)
			.param("sentAt", utc(sentAt))
			.query(UUID.class)
			.single();
	}

	PageResponse<Row> page(int page, int size) {
		long total = jdbc.sql("SELECT count(*) FROM announcement WHERE sent_at IS NOT NULL").query(Long.class).single();
		List<Row> rows = jdbc.sql(SELECT + """
				WHERE a.sent_at IS NOT NULL
				GROUP BY a.id
				ORDER BY a.sent_at DESC, a.id
				LIMIT :limit OFFSET :offset
				""")
			.param("limit", size)
			.param("offset", (long) page * size)
			.query(Row.class)
			.list();
		return PageResponse.of(rows, page, size, total);
	}

	Optional<Row> find(UUID id) {
		return jdbc.sql(SELECT + """
				WHERE a.id = :id AND a.sent_at IS NOT NULL
				GROUP BY a.id
				""")
			.param("id", id)
			.query(Row.class)
			.optional();
	}

	List<RecipientRow> recipients(UUID announcementId) {
		return jdbc.sql("""
				SELECT n.recipient_id, n.payload ->> 'about' AS about, n.read_at IS NOT NULL AS read,
				       EXISTS (SELECT 1 FROM notification_delivery d
				               WHERE d.notification_id = n.id AND d.status = 'SENT') AS pushed
				FROM notification n
				WHERE n.announcement_id = :id
				""")
			.param("id", announcementId)
			.query(RecipientRow.class)
			.list();
	}

	private static final String SELECT = """
			SELECT a.id, a.author_id, a.audience, a.target_account_id, a.training_group_id, a.event_id,
			       a.is_marketing AS marketing, a.title, a.body, a.sent_at, a.skipped_without_consent,
			       count(n.id) AS recipients, count(n.read_at) AS read,
			       count(n.id) FILTER (WHERE EXISTS (SELECT 1 FROM notification_delivery d
			                                         WHERE d.notification_id = n.id AND d.status = 'SENT')) AS push_delivered
			FROM announcement a
			LEFT JOIN notification n ON n.announcement_id = a.id
			""";

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	record NewAnnouncement(UUID authorId, AnnouncementAudience audience, @Nullable UUID accountId,
			@Nullable UUID trainingGroupId, @Nullable UUID eventId, boolean marketing, String title, String body,
			Instant sentAt, int skippedWithoutConsent) {
	}

	record Row(UUID id, UUID authorId, String audience, @Nullable UUID targetAccountId, @Nullable UUID trainingGroupId,
			@Nullable UUID eventId, boolean marketing, String title, String body, OffsetDateTime sentAt,
			int skippedWithoutConsent, int recipients, int read, int pushDelivered) {
	}

	record RecipientRow(UUID recipientId, @Nullable String about, boolean read, boolean pushed) {
	}

}
