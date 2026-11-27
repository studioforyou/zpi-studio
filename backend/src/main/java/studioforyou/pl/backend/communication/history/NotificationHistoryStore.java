package studioforyou.pl.backend.communication.history;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.shared.api.PageResponse;

/** Reads sent notifications with their delivery outcome ({@code notification}, {@code notification_delivery}). */
@Repository
class NotificationHistoryStore {

	/** The best outcome of a notification, as {@link SendStatus}. */
	private static final String STATUS = """
			CASE
			  WHEN n.read_at IS NOT NULL THEN 'READ'
			  WHEN EXISTS (SELECT 1 FROM notification_delivery d WHERE d.notification_id = n.id AND d.status = 'SENT')
			    THEN 'DELIVERED'
			  WHEN EXISTS (SELECT 1 FROM notification_delivery d WHERE d.notification_id = n.id
			                 AND d.status IN ('FAILED', 'TOKEN_INVALID'))
			    THEN 'PUSH_FAILED'
			  ELSE 'APP_ONLY'
			END""";

	private static final String FROM = """
			FROM notification n
			JOIN notification_type t ON t.id = n.notification_type_id
			WHERE n.scheduled_for <= :now
			  AND (CAST(:type AS TEXT) IS NULL OR t.code = :type)
			  AND (CAST(:account AS UUID) IS NULL OR n.recipient_id = :account)
			  AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR n.scheduled_for >= :from)
			  AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR n.scheduled_for < :to)
			""";

	private static final String COLUMNS = """
			SELECT n.id, t.code AS type, n.recipient_id, n.recipient_role, n.subject_customer_id,
			       n.payload ->> 'about' AS about, n.title, n.body, n.category = 'MARKETING' AS marketing,
			       n.announcement_id, n.payload ->> 'author' AS author, n.scheduled_for AS sent_at, n.read_at,
			""" + STATUS + " AS status\n";

	private final JdbcClient jdbc;

	NotificationHistoryStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	PageResponse<Row> page(Filter filter, Instant now, int page, int size) {
		String where = FROM + (filter.status() == null ? "" : " AND " + STATUS + " = :status\n");
		long total = bind(jdbc.sql("SELECT count(*) " + where), filter, now).query(Long.class).single();
		List<Row> rows = bind(jdbc.sql(COLUMNS + where + """
				ORDER BY n.scheduled_for DESC, n.id
				LIMIT :limit OFFSET :offset
				"""), filter, now)
			.param("limit", size)
			.param("offset", (long) page * size)
			.query(Row.class)
			.list();
		return PageResponse.of(rows, page, size, total);
	}

	Optional<Row> find(UUID id, Instant now) {
		return jdbc.sql(COLUMNS + """
				FROM notification n
				JOIN notification_type t ON t.id = n.notification_type_id
				WHERE n.id = :id AND n.scheduled_for <= :now
				""")
			.param("id", id)
			.param("now", utc(now))
			.query(Row.class)
			.optional();
	}

	List<DeliveryRow> deliveries(UUID notificationId) {
		return jdbc.sql("""
				SELECT attempted_at, channel, status, error
				FROM notification_delivery
				WHERE notification_id = :id
				ORDER BY attempted_at, id
				""")
			.param("id", notificationId)
			.query(DeliveryRow.class)
			.list();
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, Filter filter, Instant now) {
		sql = sql.param("now", utc(now))
			.param("type", filter.type(), java.sql.Types.VARCHAR)
			.param("account", filter.accountId(), java.sql.Types.OTHER)
			.param("from", filter.from() == null ? null : utc(filter.from()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
			.param("to", filter.to() == null ? null : utc(filter.to()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
		return filter.status() == null ? sql : sql.param("status", filter.status().name());
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	record Filter(@Nullable String type, @Nullable UUID accountId, @Nullable Instant from, @Nullable Instant to,
			@Nullable SendStatus status) {
	}

	record Row(UUID id, String type, UUID recipientId, @Nullable String recipientRole, @Nullable UUID subjectCustomerId,
			@Nullable String about, String title, @Nullable String body, boolean marketing,
			@Nullable UUID announcementId, @Nullable String author, OffsetDateTime sentAt,
			@Nullable OffsetDateTime readAt, String status) {
	}

	record DeliveryRow(OffsetDateTime attemptedAt, String channel, String status, @Nullable String error) {
	}

}
