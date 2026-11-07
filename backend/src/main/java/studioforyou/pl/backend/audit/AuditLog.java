package studioforyou.pl.backend.audit;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Appends change records to the shared audit log ({@code audit_log}). Records are never
 * updated or deleted. Call it inside the transaction of the change, so the change and its
 * record are saved together.
 */
@Repository
public class AuditLog {

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
	 * Value of one field before and after the change.
	 */
	public record Change(@JsonProperty("old") @Nullable Object oldValue,
			@JsonProperty("new") @Nullable Object newValue) {
	}

}
