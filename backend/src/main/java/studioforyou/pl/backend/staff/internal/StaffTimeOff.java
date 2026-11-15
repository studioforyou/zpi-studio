package studioforyou.pl.backend.staff.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.staff.TimeOffChanged;
import studioforyou.pl.backend.staff.internal.TimeOffDtos.TimeOffResponse;

/**
 * Leave and unavailability of staff members: trainers request it, administrators approve or
 * reject it, and either side can cancel it before it ends. A trainer can still change a request
 * while it waits for a decision. Requested and approved time off of one staff member never
 * overlaps. The studio's decisions publish {@link TimeOffChanged}.
 */
@Service
@Transactional
class StaffTimeOff {

	/** The longest single period; a longer absence is recorded as several periods. */
	static final Duration MAX_LENGTH = Duration.ofDays(366);

	// Unicode White_Space plus BOM, matching the clients' String.trim().
	private static final Pattern EDGE_WHITESPACE = Pattern
		.compile("^[\\s\\p{Z}\\u0085\\uFEFF]+|[\\s\\p{Z}\\u0085\\uFEFF]+$");

	private static final String SELECT = """
			SELECT t.id, t.staff_id, s.first_name || ' ' || s.last_name AS staff_display_name, t.kind, t.status,
			       t.starts_at, t.ends_at, t.reason, t.requested_at, t.decided_at, t.decision_note,
			       d.first_name || ' ' || d.last_name AS decided_by_name,
			       (t.decided_by IS NOT NULL AND t.decided_at = t.requested_at) AS recorded_by_studio
			FROM staff_time_off t
			JOIN staff_profile s ON s.id = t.staff_id
			LEFT JOIN staff_profile d ON d.account_id = t.decided_by
			""";

	private final JdbcClient jdbc;

	private final ApplicationEventPublisher events;

	private final Clock clock = Clock.systemUTC();

	StaffTimeOff(JdbcClient jdbc, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	/** Time off of one staff member, the latest first. */
	@Transactional(readOnly = true)
	List<TimeOffResponse> findOwn(UUID staffId) {
		return this.jdbc.sql(SELECT + """
				WHERE t.staff_id = :staffId
				ORDER BY t.starts_at DESC, t.requested_at DESC
				""").param("staffId", staffId).query(StaffTimeOff::map).list();
	}

	/**
	 * Time off matching all given filters, earliest start first.
	 *
	 * @param statuses only these statuses; all when empty
	 * @param from with {@code to}, only time off that overlaps {@code [from, to)}
	 */
	@Transactional(readOnly = true)
	List<TimeOffResponse> find(Collection<TimeOffStatus> statuses, @Nullable UUID staffId, @Nullable Instant from,
			@Nullable Instant to) {
		if (from != null && to != null && !to.isAfter(from)) {
			throw ApiException.invalidField("to", FieldErrorCode.INVALID_VALUE, "to must be after from.");
		}
		List<String> conditions = new ArrayList<>();
		if (!statuses.isEmpty()) {
			conditions.add("t.status IN (:statuses)");
		}
		if (staffId != null) {
			conditions.add("t.staff_id = :staffId");
		}
		if (from != null) {
			conditions.add("t.ends_at > :from");
		}
		if (to != null) {
			conditions.add("t.starts_at < :to");
		}
		String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";
		JdbcClient.StatementSpec statement = this.jdbc
			.sql(SELECT + where + "ORDER BY t.starts_at, s.last_name, s.first_name");
		if (!statuses.isEmpty()) {
			statement = statement.param("statuses", statuses.stream().map(Enum::name).toList());
		}
		if (staffId != null) {
			statement = statement.param("staffId", staffId);
		}
		if (from != null) {
			statement = statement.param("from", utc(from));
		}
		if (to != null) {
			statement = statement.param("to", utc(to));
		}
		return statement.query(StaffTimeOff::map).list();
	}

	/** A trainer's request; it waits for an administrator's decision. */
	TimeOffResponse request(UUID staffId, TimeOffKind kind, Instant startsAt, Instant endsAt,
			@Nullable String reason) {
		validatePeriod(startsAt, endsAt, false);
		return insert(staffId, kind, startsAt, endsAt, reason, TimeOffStatus.REQUESTED, null);
	}

	/**
	 * Time off an administrator records, e.g. sick leave reported by phone. It is approved at once
	 * and may lie in the past.
	 */
	TimeOffResponse record(UUID adminAccountId, UUID staffId, TimeOffKind kind, Instant startsAt, Instant endsAt,
			@Nullable String reason) {
		validatePeriod(startsAt, endsAt, true);
		TimeOffResponse recorded = insert(staffId, kind, startsAt, endsAt, reason, TimeOffStatus.APPROVED,
				adminAccountId);
		publish(recorded, TimeOffChanged.Change.APPROVED);
		return recorded;
	}

	/**
	 * Changes the trainer's own request while it waits for a decision; another trainer's is
	 * reported as missing.
	 */
	TimeOffResponse updateOwn(UUID staffId, UUID timeOffId, TimeOffKind kind, Instant startsAt, Instant endsAt,
			@Nullable String reason) {
		TimeOffResponse current = lock(timeOffId);
		if (!current.staffId().equals(staffId)) {
			throw notFound();
		}
		if (current.status() != TimeOffStatus.REQUESTED) {
			throw new ApiException(ErrorCode.TIME_OFF_ALREADY_DECIDED,
					"Only a request that waits for a decision can be changed.",
					Map.of("status", current.status().name()));
		}
		validatePeriod(startsAt, endsAt, false);
		String trimmedReason = text(reason, "reason");
		lockStaff(staffId);
		rejectOverlap(staffId, startsAt, endsAt, timeOffId);
		this.jdbc.sql("""
				UPDATE staff_time_off
				SET kind = :kind, starts_at = :startsAt, ends_at = :endsAt, reason = :reason
				WHERE id = :id
				""")
			.param("kind", kind.name())
			.param("startsAt", utc(startsAt))
			.param("endsAt", utc(endsAt))
			.param("reason", trimmedReason)
			.param("id", timeOffId)
			.update();
		return get(timeOffId);
	}

	TimeOffResponse approve(UUID timeOffId, UUID adminAccountId, @Nullable String note) {
		return decide(timeOffId, adminAccountId, note, TimeOffStatus.APPROVED);
	}

	TimeOffResponse reject(UUID timeOffId, UUID adminAccountId, @Nullable String note) {
		return decide(timeOffId, adminAccountId, note, TimeOffStatus.REJECTED);
	}

	/** Cancels the trainer's own time off; another trainer's is reported as missing. */
	TimeOffResponse cancelOwn(UUID staffId, UUID timeOffId) {
		TimeOffResponse current = lock(timeOffId);
		if (!current.staffId().equals(staffId)) {
			throw notFound();
		}
		return cancel(current);
	}

	/** Cancels any staff member's time off, e.g. when they come back from leave early. */
	TimeOffResponse cancel(UUID timeOffId) {
		TimeOffResponse cancelled = cancel(lock(timeOffId));
		publish(cancelled, TimeOffChanged.Change.CANCELLED);
		return cancelled;
	}

	private TimeOffResponse cancel(TimeOffResponse current) {
		if (!current.status().isActive() || !current.endsAt().isAfter(this.clock.instant())) {
			throw new ApiException(ErrorCode.TIME_OFF_NOT_CANCELLABLE,
					"Only requested or approved time off that has not ended can be cancelled.");
		}
		this.jdbc.sql("UPDATE staff_time_off SET status = 'CANCELLED' WHERE id = :id")
			.param("id", current.id())
			.update();
		return get(current.id());
	}

	private TimeOffResponse decide(UUID timeOffId, UUID adminAccountId, @Nullable String note,
			TimeOffStatus decision) {
		TimeOffResponse current = lock(timeOffId);
		if (current.status() != TimeOffStatus.REQUESTED) {
			throw new ApiException(ErrorCode.TIME_OFF_ALREADY_DECIDED,
					"Only a request that waits for a decision can be approved or rejected.",
					Map.of("status", current.status().name()));
		}
		this.jdbc.sql("""
				UPDATE staff_time_off
				SET status = :status, decided_by = :decidedBy, decided_at = :decidedAt, decision_note = :note
				WHERE id = :id
				""")
			.param("status", decision.name())
			.param("decidedBy", adminAccountId)
			.param("decidedAt", utc(this.clock.instant()))
			.param("note", text(note, "note"))
			.param("id", timeOffId)
			.update();
		TimeOffResponse decided = get(timeOffId);
		publish(decided, decision == TimeOffStatus.APPROVED ? TimeOffChanged.Change.APPROVED
				: TimeOffChanged.Change.REJECTED);
		return decided;
	}

	private void publish(TimeOffResponse timeOff, TimeOffChanged.Change change) {
		UUID accountId = this.jdbc.sql("SELECT account_id FROM staff_profile WHERE id = :staffId")
			.param("staffId", timeOff.staffId())
			.query(UUID.class)
			.single();
		this.events.publishEvent(new TimeOffChanged(timeOff.id(), accountId, change, timeOff.kind().name(),
				timeOff.startsAt(), timeOff.endsAt(), timeOff.decisionNote()));
	}

	private TimeOffResponse insert(UUID staffId, TimeOffKind kind, Instant startsAt, Instant endsAt,
			@Nullable String reason, TimeOffStatus status, @Nullable UUID decidedBy) {
		String trimmedReason = text(reason, "reason");
		lockStaff(staffId);
		rejectOverlap(staffId, startsAt, endsAt, null);
		Instant now = this.clock.instant();
		UUID id = this.jdbc.sql("""
				INSERT INTO staff_time_off (staff_id, kind, starts_at, ends_at, status, reason, requested_at,
				                            decided_by, decided_at)
				VALUES (:staffId, :kind, :startsAt, :endsAt, :status, :reason, :now, :decidedBy, :decidedAt)
				RETURNING id
				""")
			.param("staffId", staffId)
			.param("kind", kind.name())
			.param("startsAt", utc(startsAt))
			.param("endsAt", utc(endsAt))
			.param("status", status.name())
			.param("reason", trimmedReason)
			.param("now", utc(now))
			.param("decidedBy", decidedBy)
			.param("decidedAt", decidedBy == null ? null : utc(now))
			.query(UUID.class)
			.single();
		return get(id);
	}

	/** Serializes changes of one staff member so two overlapping requests cannot both pass. */
	private void lockStaff(UUID staffId) {
		this.jdbc.sql("SELECT id FROM staff_profile WHERE id = :staffId FOR UPDATE")
			.param("staffId", staffId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> ApiException.invalidField("staffId", FieldErrorCode.INVALID_VALUE,
					"This staff member does not exist."));
	}

	private void rejectOverlap(UUID staffId, Instant startsAt, Instant endsAt, @Nullable UUID ignoredId) {
		this.jdbc.sql("""
				SELECT id FROM staff_time_off
				WHERE staff_id = :staffId AND status IN ('REQUESTED', 'APPROVED')
				  AND starts_at < :endsAt AND ends_at > :startsAt
				  AND (CAST(:ignoredId AS UUID) IS NULL OR id <> CAST(:ignoredId AS UUID))
				ORDER BY starts_at
				LIMIT 1
				""")
			.param("staffId", staffId)
			.param("startsAt", utc(startsAt))
			.param("endsAt", utc(endsAt))
			.param("ignoredId", ignoredId)
			.query(UUID.class)
			.optional()
			.ifPresent((conflict) -> {
				throw new ApiException(ErrorCode.TIME_OFF_OVERLAP,
						"The staff member already has requested or approved time off in this period.",
						Map.of("conflictingTimeOffId", conflict));
			});
	}

	private void validatePeriod(Instant startsAt, Instant endsAt, boolean pastAllowed) {
		if (!endsAt.isAfter(startsAt)) {
			throw ApiException.invalidField("endsAt", FieldErrorCode.INVALID_VALUE, "endsAt must be after startsAt.");
		}
		if (Duration.between(startsAt, endsAt).compareTo(MAX_LENGTH) > 0) {
			throw ApiException.invalidField("endsAt", FieldErrorCode.OUT_OF_RANGE,
					"Time off can last at most 366 days.");
		}
		if (!pastAllowed && !endsAt.isAfter(this.clock.instant())) {
			throw ApiException.invalidField("endsAt", FieldErrorCode.OUT_OF_RANGE,
					"Time off that has already ended cannot be requested.");
		}
	}

	private TimeOffResponse lock(UUID timeOffId) {
		this.jdbc.sql("SELECT id FROM staff_time_off WHERE id = :id FOR UPDATE")
			.param("id", timeOffId)
			.query(UUID.class)
			.optional()
			.orElseThrow(StaffTimeOff::notFound);
		return get(timeOffId);
	}

	private TimeOffResponse get(UUID timeOffId) {
		return this.jdbc.sql(SELECT + "WHERE t.id = :id")
			.param("id", timeOffId)
			.query(StaffTimeOff::map)
			.optional()
			.orElseThrow(StaffTimeOff::notFound);
	}

	private static @Nullable String text(@Nullable String value, String field) {
		if (value == null) {
			return null;
		}
		String trimmed = EDGE_WHITESPACE.matcher(value).replaceAll("");
		if (trimmed.length() > TimeOffDtos.MAX_TEXT_LENGTH) {
			throw ApiException.invalidField(field, FieldErrorCode.TOO_LONG,
					field + " can have at most " + TimeOffDtos.MAX_TEXT_LENGTH + " characters.");
		}
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "This time off does not exist.");
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static TimeOffResponse map(ResultSet rs, int rowNum) throws SQLException {
		return new TimeOffResponse(rs.getObject("id", UUID.class), rs.getObject("staff_id", UUID.class),
				rs.getString("staff_display_name"), TimeOffKind.valueOf(rs.getString("kind")),
				TimeOffStatus.valueOf(rs.getString("status")), instant(rs, "starts_at"), instant(rs, "ends_at"),
				rs.getString("reason"), instant(rs, "requested_at"), instant(rs, "decided_at"),
				rs.getString("decision_note"), rs.getString("decided_by_name"), rs.getBoolean("recorded_by_studio"));
	}

}
