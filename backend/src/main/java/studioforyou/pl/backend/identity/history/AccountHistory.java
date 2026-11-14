package studioforyou.pl.backend.identity.history;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.identity.AccountStatus;
import studioforyou.pl.backend.identity.access.OverrideEffect;
import studioforyou.pl.backend.identity.history.AccountHistoryEntry.Person;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * The history of account statuses, roles and permissions, newest first. Status changes come from
 * {@code account_status_change}, role and permission changes from the audit log entries
 * {@code AccessManagement} records.
 */
@Service
@Transactional(readOnly = true)
class AccountHistory {

	/** The audit log entries of role and permission changes of accounts. */
	private static final String ACCESS_CHANGES = """
			l.module = 'identity' AND l.entity_type = 'account'
			  AND l.action IN ('ROLE_GRANTED', 'ROLE_REVOKED', 'PERMISSION_OVERRIDE_ADDED', 'PERMISSION_OVERRIDE_REMOVED')""";

	/** The name of the person each account belongs to, from its client or else its staff profile. */
	private static final String PEOPLE = """
			people AS (
			    SELECT a.id, a.email,
			           COALESCE(c.first_name, s.first_name) AS first_name,
			           COALESCE(c.last_name, s.last_name) AS last_name
			    FROM account a
			    LEFT JOIN customer_profile c ON c.account_id = a.id
			    LEFT JOIN staff_profile s ON s.account_id = a.id
			)""";

	/**
	 * Every entry with its author. A status change without an author that only the account's owner can
	 * make (accepting the invitation, registering) is the owner's; any other one is the system's.
	 * {@code step} puts the creation of an account before the other changes saved in the same
	 * transaction, which share its time.
	 */
	private static final String HISTORY = """
			WITH history AS (
			    SELECT 'status-' || c.id AS id, c.changed_at AS occurred_at, 'STATUS_CHANGED' AS type,
			           c.account_id,
			           COALESCE(c.changed_by,
			                    CASE WHEN (c.from_status = 'INVITED' AND c.to_status = 'ACTIVE')
			                           OR (c.from_status IS NULL AND c.to_status = 'PENDING_APPROVAL')
			                         THEN c.account_id END) AS actor_id,
			           c.from_status, c.to_status, NULL::text AS changes, c.reason,
			           CASE WHEN c.from_status IS NULL THEN 0 ELSE 1 END AS step, NULL::bigint AS seq
			    FROM account_status_change c
			    UNION ALL
			    SELECT 'audit-' || l.id, l.occurred_at, l.action, l.entity_id, l.actor_account_id,
			           NULL, NULL, l.changes::text, l.reason, 1, l.id
			    FROM audit_log l
			    WHERE %s
			),
			%s
			""".formatted(ACCESS_CHANGES, PEOPLE);

	private static final String FROM = """
			FROM history h
			JOIN people target ON target.id = h.account_id
			LEFT JOIN people actor ON actor.id = h.actor_id
			""";

	private static final String COLUMNS = """
			SELECT h.id, h.occurred_at, h.type, h.account_id, h.actor_id, h.from_status, h.to_status, h.changes,
			       h.reason,
			       target.email AS target_email, target.first_name AS target_first_name,
			       target.last_name AS target_last_name,
			       actor.email AS actor_email, actor.first_name AS actor_first_name, actor.last_name AS actor_last_name
			""";

	private static final String ORDER = """
			ORDER BY h.occurred_at DESC, h.step DESC, h.seq DESC NULLS LAST, h.id DESC
			LIMIT :limit OFFSET :offset
			""";

	private static final String PERMISSION_KEY = "permissionOverride.";

	/**
	 * Reasons the backend saves when the person gave none. The entry's type already says the same, so
	 * the history leaves them out.
	 */
	private static final Set<String> GENERATED_REASONS = Set.of("Invitation accepted", "Created by an administrator",
			"Suspended by an administrator", "Reactivated by an administrator", "Closed by an administrator");

	private static final TypeReference<Map<String, AuditLog.Change>> CHANGES = new TypeReference<>() {
	};

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	private final StudioSettings studioSettings;

	AccountHistory(JdbcClient jdbc, JsonMapper jsonMapper, StudioSettings studioSettings) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
		this.studioSettings = studioSettings;
	}

	/**
	 * One page of the entries that match the filter, newest first.
	 *
	 * @throws ApiException {@code VALIDATION_FAILED} if {@code to} is before {@code from}
	 */
	public PageResponse<AccountHistoryEntry> search(AccountHistoryFilter filter, int page, int size) {
		if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE, "to must not be before from.");
		}
		Map<String, Object> params = new HashMap<>();
		String where = where(filter, params);
		long total = jdbc.sql(HISTORY + "SELECT count(*)\n" + FROM + where)
			.params(params)
			.query(Long.class)
			.single();
		params.put("limit", size);
		params.put("offset", (long) page * size);
		List<AccountHistoryEntry> items = jdbc.sql(HISTORY + COLUMNS + FROM + where + ORDER)
			.params(params)
			.query((rs, rowNum) -> entry(rs))
			.list();
		return PageResponse.of(items, page, size, total);
	}

	/** The accounts that changed the status, roles or permissions of an account, sorted by name. */
	public List<Person> actors() {
		return jdbc.sql("WITH " + PEOPLE + """
				,
				actors AS (
				    SELECT changed_by AS id FROM account_status_change WHERE changed_by IS NOT NULL
				    UNION
				    SELECT l.actor_account_id FROM audit_log l WHERE l.actor_account_id IS NOT NULL AND %s
				)
				SELECT p.id, p.email, p.first_name, p.last_name
				FROM actors
				JOIN people p ON p.id = actors.id
				ORDER BY p.last_name NULLS LAST, p.first_name, lower(p.email), p.id
				""".formatted(ACCESS_CHANGES))
			.query((rs, rowNum) -> new Person(rs.getObject("id", UUID.class),
					displayName(rs.getString("first_name"), rs.getString("last_name"), rs.getString("email"))))
			.list();
	}

	private String where(AccountHistoryFilter filter, Map<String, Object> params) {
		List<String> conditions = new ArrayList<>();
		if (filter.accountId() != null) {
			conditions.add("h.account_id = :accountId");
			params.put("accountId", filter.accountId());
		}
		if (filter.actorId() != null) {
			conditions.add("h.actor_id = :actorId");
			params.put("actorId", filter.actorId());
		}
		if (!filter.types().isEmpty()) {
			conditions.add("h.type IN (:types)");
			params.put("types", filter.types().stream().map(Enum::name).toList());
		}
		String query = filter.query();
		if (query != null && !query.isBlank()) {
			conditions.add("""
					(target.first_name || ' ' || target.last_name ILIKE :pattern
					   OR target.last_name || ' ' || target.first_name ILIKE :pattern
					   OR target.email ILIKE :pattern)""");
			params.put("pattern", "%" + escapeLike(query.strip()) + "%");
		}
		if (filter.from() != null) {
			conditions.add("h.occurred_at >= :start");
			params.put("start", filter.from().atStartOfDay(studioSettings.zone()).toOffsetDateTime());
		}
		if (filter.to() != null) {
			conditions.add("h.occurred_at < :end");
			params.put("end", filter.to().plusDays(1).atStartOfDay(studioSettings.zone()).toOffsetDateTime());
		}
		return conditions.isEmpty() ? "" : "WHERE " + String.join("\n  AND ", conditions) + "\n";
	}

	private AccountHistoryEntry entry(ResultSet rs) throws SQLException {
		AccountHistoryType type = AccountHistoryType.valueOf(rs.getString("type"));
		Map<String, AuditLog.Change> changes = changes(rs.getString("changes"));
		UUID actorId = rs.getObject("actor_id", UUID.class);
		Person actor = (actorId == null) ? null
				: new Person(actorId, displayName(rs.getString("actor_first_name"), rs.getString("actor_last_name"),
						rs.getString("actor_email")));
		String permission = permission(changes);
		return new AccountHistoryEntry(rs.getString("id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
				type,
				new Person(rs.getObject("account_id", UUID.class), displayName(rs.getString("target_first_name"),
						rs.getString("target_last_name"), rs.getString("target_email"))),
				actor, status(rs.getString("from_status")), status(rs.getString("to_status")), role(type, changes),
				permission, (permission == null) ? null : effect(type, changes.get(PERMISSION_KEY + permission)),
				reason(rs.getString("reason")));
	}

	private Map<String, AuditLog.Change> changes(@Nullable String json) {
		return (json == null) ? Map.of() : jsonMapper.readValue(json, CHANGES);
	}

	/**
	 * The granted or revoked role: the one the {@code roles} lists before and after differ in. Older
	 * records name it in {@code role}.
	 */
	private static @Nullable String role(AccountHistoryType type, Map<String, AuditLog.Change> changes) {
		if (type != AccountHistoryType.ROLE_GRANTED && type != AccountHistoryType.ROLE_REVOKED) {
			return null;
		}
		boolean granted = type == AccountHistoryType.ROLE_GRANTED;
		AuditLog.Change roles = changes.get("roles");
		if (roles != null) {
			List<String> before = strings(roles.oldValue());
			List<String> after = strings(roles.newValue());
			List<String> others = granted ? before : after;
			return (granted ? after : before).stream().filter(code -> !others.contains(code)).findFirst().orElse(null);
		}
		AuditLog.Change role = changes.get("role");
		Object code = (role == null) ? null : (granted ? role.newValue() : role.oldValue());
		return (code == null) ? null : code.toString();
	}

	private static @Nullable String permission(Map<String, AuditLog.Change> changes) {
		return changes.keySet()
			.stream()
			.filter(key -> key.startsWith(PERMISSION_KEY))
			.map(key -> key.substring(PERMISSION_KEY.length()))
			.findFirst()
			.orElse(null);
	}

	/** An added override's new effect, a removed override's old one. */
	private static @Nullable OverrideEffect effect(AccountHistoryType type, AuditLog.@Nullable Change change) {
		if (change == null) {
			return null;
		}
		Object effect = (type == AccountHistoryType.PERMISSION_OVERRIDE_REMOVED) ? change.oldValue()
				: change.newValue();
		return (effect == null) ? null : OverrideEffect.valueOf(effect.toString());
	}

	private static List<String> strings(@Nullable Object list) {
		return (list instanceof Collection<?> values) ? values.stream().map(String::valueOf).toList() : List.of();
	}

	private static @Nullable AccountStatus status(@Nullable String status) {
		return (status == null) ? null : AccountStatus.valueOf(status);
	}

	private static @Nullable String reason(@Nullable String reason) {
		return (reason == null || reason.isBlank() || GENERATED_REASONS.contains(reason)) ? null : reason;
	}

	private static String displayName(@Nullable String firstName, @Nullable String lastName, String email) {
		return (firstName == null) ? email : firstName + " " + lastName;
	}

	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
