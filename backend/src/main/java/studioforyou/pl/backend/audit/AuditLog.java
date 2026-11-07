package studioforyou.pl.backend.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Appends change records to the shared audit log ({@code audit_log}). Records are never
 * updated or deleted. Call it inside the transaction of the change, so the change and its
 * record are saved together. The owner of a record can read its history back.
 */
@Repository
public class AuditLog {

	private static final TypeReference<Map<String, Change>> CHANGES = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	public AuditLog(JdbcClient jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	public void append(Entry entry) {
		jdbc.sql("""
				INSERT INTO audit_log (actor_account_id, module, entity_type, entity_id, action, changes, reason)
				VALUES (:actor, :module, :entityType, :entityId, :action, CAST(:changes AS jsonb), :reason)
				""")
			.param("actor", entry.actorAccountId())
			.param("module", entry.module())
			.param("entityType", entry.entityType())
			.param("entityId", entry.entityId())
			.param("action", entry.action())
			.param("changes", (entry.changes() != null) ? jsonMapper.writeValueAsString(entry.changes()) : null)
			.param("reason", entry.reason())
			.update();
	}

	/**
	 * The recorded changes of the given records of one owner, oldest first, so the owner can show
	 * their history. Unknown ids have no records.
	 *
	 * @param module the module that owns the records, e.g. {@code booking}
	 * @param entityType the records' type, e.g. {@code reservation}
	 */
	public List<Recorded> history(String module, String entityType, Collection<UUID> entityIds) {
		if (entityIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id, occurred_at, actor_account_id, entity_id, action, changes::text AS changes, reason
				FROM audit_log
				WHERE module = :module AND entity_type = :entityType AND entity_id IN (:entityIds)
				ORDER BY occurred_at, id
				""")
			.param("module", module)
			.param("entityType", entityType)
			.param("entityIds", List.copyOf(entityIds))
			.query((rs, row) -> new Recorded(rs.getLong("id"),
					rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
					rs.getObject("actor_account_id", UUID.class), rs.getObject("entity_id", UUID.class),
					rs.getString("action"), changes(rs.getString("changes")), rs.getString("reason")))
			.list();
	}

	private Map<String, Change> changes(@Nullable String json) {
		return (json == null) ? Map.of() : jsonMapper.readValue(json, CHANGES);
	}

	/**
	 * One change record.
	 *
	 * @param actorAccountId the account that made the change, or {@code null} for the system
	 * @param module the module that owns the changed record, e.g. {@code identity}
	 * @param entityType the changed record's type, e.g. {@code account}
	 * @param entityId the changed record's id
	 * @param action what happened, e.g. {@code ROLE_GRANTED}
	 * @param changes changed fields as {@code {"field": {"old": ..., "new": ...}}}; never health data
	 * @param reason why the change was made, if given
	 */
	public record Entry(@Nullable UUID actorAccountId, String module, String entityType, UUID entityId,
			String action, @Nullable Map<String, Change> changes, @Nullable String reason) {
	}

	/**
	 * A saved change record.
	 *
	 * @param actorAccountId the account that made the change, or {@code null} for the system
	 * @param changes changed fields; empty when none were recorded
	 * @param reason why the change was made, or {@code null}
	 */
	public record Recorded(long id, Instant occurredAt, @Nullable UUID actorAccountId, UUID entityId, String action,
			Map<String, Change> changes, @Nullable String reason) {
	}

	/**
	 * Value of one field before and after the change.
	 */
	public record Change(@JsonProperty("old") @Nullable Object oldValue,
			@JsonProperty("new") @Nullable Object newValue) {
	}

}
