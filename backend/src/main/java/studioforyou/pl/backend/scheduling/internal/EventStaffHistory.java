package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedStaffResponse;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Actual staff changes, saved in the change's transaction, independently of recipient notices. */
@Component
class EventStaffHistory {

	private static final TypeReference<List<AssignedStaffResponse>> MEMBERS = new TypeReference<>() {};

	private final AuditLog audit;
	private final ScheduleQueries queries;
	private final StaffProfiles staff;
	private final JdbcClient jdbc;
	private final JsonMapper json;

	EventStaffHistory(AuditLog audit, ScheduleQueries queries, StaffProfiles staff, JdbcClient jdbc,
			JsonMapper json) {
		this.audit = audit;
		this.queries = queries;
		this.staff = staff;
		this.jdbc = jdbc;
		this.json = json;
	}

	enum Kind { MANUAL, SERIES_RESTORED, SUBSTITUTION, AUTOMATIC_SUBSTITUTION }

	record Entry(String id, Instant occurredAt, Kind kind, @Nullable UUID actorAccountId,
			@Nullable String actorName, List<AssignedStaffResponse> before, List<AssignedStaffResponse> after) {}

	Map<UUID, List<AssignedStaffResponse>> snapshots(Collection<UUID> eventIds) {
		return this.queries.findEventsById(eventIds).stream().collect(Collectors.toMap(
				ScheduleDtos.ScheduleEventResponse::id,
				(event) -> event.staff().stream().sorted(Comparator.comparing(AssignedStaffResponse::staffId)).toList()));
	}

	void record(Map<UUID, List<AssignedStaffResponse>> before, UUID actor, Kind kind,
			@Nullable UUID substitutionRequestId) {
		Map<UUID, List<AssignedStaffResponse>> after = snapshots(before.keySet());
		before.forEach((eventId, previous) -> {
			List<AssignedStaffResponse> current = after.get(eventId);
			// Reordering the same people or changing rooms alone is not a change of staff.
			if (current == null || keys(previous).equals(keys(current))) return;
			Map<String, AuditLog.Change> changes = new java.util.LinkedHashMap<>();
			changes.put("staff", new AuditLog.Change(previous, current));
			if (substitutionRequestId != null) {
				changes.put("substitutionRequestId", new AuditLog.Change(null, substitutionRequestId));
			}
			this.audit.append(new AuditLog.Entry(actor, "scheduling", "schedule_event", eventId,
					"STAFF_" + kind.name(), changes, null));
		});
	}

	@Transactional(readOnly = true)
	List<Entry> history(UUID eventId) {
		this.queries.findEvent(eventId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
		List<AuditLog.Recorded> recorded = this.audit.history("scheduling", "schedule_event", List.of(eventId))
			.stream().filter((entry) -> switch (entry.action()) {
				case "STAFF_MANUAL", "STAFF_SERIES_RESTORED", "STAFF_SUBSTITUTION", "STAFF_AUTOMATIC_SUBSTITUTION" -> true;
				default -> false;
			}).toList();
		var linkedRequests = recorded.stream().map((entry) -> entry.changes().get("substitutionRequestId"))
			.filter(Objects::nonNull).map((change) -> Objects.toString(change.newValue())).collect(Collectors.toSet());
		List<Entry> entries = new ArrayList<>();
		for (AuditLog.Recorded entry : recorded) {
			AuditLog.Change change = entry.changes().get("staff");
			if (change != null) {
				entries.add(new Entry("audit-" + entry.id(), entry.occurredAt(),
						Kind.valueOf(entry.action().substring("STAFF_".length())), entry.actorAccountId(), null,
						this.json.convertValue(change.oldValue(), MEMBERS), this.json.convertValue(change.newValue(), MEMBERS)));
			}
		}
		// Older approved requests already contain who replaced whom and who made the decision.
		// Do not infer a manual change from a notification, or claim to know an unrecorded date.
		var legacy = this.jdbc.sql("""
				SELECT id, original_staff_id, substitute_staff_id, decided_at, decided_by
				FROM substitution_request WHERE event_id = :event AND status = 'APPROVED'
				AND substitute_staff_id IS NOT NULL AND decided_at IS NOT NULL
				ORDER BY decided_at, id
				""").param("event", eventId).query((rs, row) -> new Legacy(rs.getObject("id", UUID.class),
					rs.getObject("original_staff_id", UUID.class), rs.getObject("substitute_staff_id", UUID.class),
					rs.getObject("decided_at", OffsetDateTime.class).toInstant(), rs.getObject("decided_by", UUID.class)))
			.list().stream().filter((entry) -> !linkedRequests.contains(entry.id().toString())).toList();
		var members = this.staff.findAllById(legacy.stream().flatMap((entry) ->
			java.util.stream.Stream.of(entry.original(), entry.substitute())).distinct().toList())
			.stream().collect(Collectors.toMap(StaffProfiles.StaffMember::id, StaffProfiles.StaffMember::displayName));
		for (Legacy entry : legacy) {
			entries.add(new Entry("substitution-" + entry.id(), entry.at(), Kind.SUBSTITUTION, entry.actor(), null,
					List.of(new AssignedStaffResponse(entry.original(), members.get(entry.original()), StaffRole.LEAD)),
					List.of(new AssignedStaffResponse(entry.substitute(), members.get(entry.substitute()), StaffRole.LEAD))));
		}
		var names = this.staff.displayNamesByAccount(entries.stream().map(Entry::actorAccountId)
			.filter(Objects::nonNull).distinct().toList());
		return entries.stream().sorted(Comparator.comparing(Entry::occurredAt).thenComparing(Entry::id).reversed())
			.map((entry) -> new Entry(entry.id(), entry.occurredAt(), entry.kind(), entry.actorAccountId(),
					entry.actorAccountId() == null ? null : names.get(entry.actorAccountId()), entry.before(), entry.after()))
			.toList();
	}

	private record Legacy(UUID id, UUID original, UUID substitute, Instant at, @Nullable UUID actor) {}

	private static List<String> keys(List<AssignedStaffResponse> members) {
		return members.stream().map((member) -> member.staffId() + ":" + member.role()).toList();
	}
}
