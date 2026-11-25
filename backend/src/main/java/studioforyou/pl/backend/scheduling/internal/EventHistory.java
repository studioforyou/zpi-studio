package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedStaffResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** History of one occurrence, independent of recipient notices and other occurrences of its series. */
@Component
class EventHistory {

	private static final Set<String> FIELDS = Set.of("time", "title", "capacity", "staff", "resources",
			"visibility", "status", "guestName", "blockReason", "forceRun");

	private final AuditLog audit;
	private final ScheduleQueries queries;
	private final StaffProfiles staff;
	private final CustomerProfiles customers;
	private final JdbcClient jdbc;

	EventHistory(AuditLog audit, ScheduleQueries queries, StaffProfiles staff, CustomerProfiles customers, JdbcClient jdbc) {
		this.audit = audit;
		this.queries = queries;
		this.staff = staff;
		this.customers = customers;
		this.jdbc = jdbc;
	}

	enum Kind { CREATED, MANUAL, SERIES_RESTORED, SUBSTITUTION, AUTOMATIC_SUBSTITUTION, CANCELLED, COMPLETED, FORCE_RUN_CHANGED }

	record Time(Instant startsAt, Instant endsAt) {}

	record Entry(String id, Instant occurredAt, Kind kind, @Nullable UUID actorAccountId,
			@Nullable String actorName, Map<String, AuditLog.Change> changes, @Nullable String reason) {}

	Map<UUID, ScheduleEventResponse> snapshots(Collection<UUID> eventIds) {
		return this.queries.findEventsById(eventIds).stream().collect(Collectors.toMap(ScheduleEventResponse::id, event -> event));
	}

	/** Called after the write is flushed, inside its transaction, so failed writes leave no history. */
	void record(Map<UUID, ScheduleEventResponse> before, UUID actor, Kind kind, @Nullable UUID substitutionRequestId) {
		Map<UUID, ScheduleEventResponse> after = snapshots(before.keySet());
		before.forEach((eventId, previous) -> {
			ScheduleEventResponse current = after.get(eventId);
			if (current == null) return;
			Map<String, AuditLog.Change> changes = changes(previous, current);
			if (changes.isEmpty()) return;
			if (substitutionRequestId != null) {
				changes.put("substitutionRequestId", new AuditLog.Change(null, substitutionRequestId));
			}
			this.audit.append(new AuditLog.Entry(actor, "scheduling", "schedule_event", eventId,
					"EVENT_" + kind.name(), changes, null));
		});
	}

	private static Map<String, AuditLog.Change> changes(ScheduleEventResponse before, ScheduleEventResponse after) {
		Map<String, AuditLog.Change> changes = new LinkedHashMap<>();
		changed(changes, "time", new Time(before.startsAt(), before.endsAt()), new Time(after.startsAt(), after.endsAt()));
		changed(changes, "title", before.title(), after.title());
		changed(changes, "capacity", before.capacity(), after.capacity());
		changed(changes, "visibility", before.visibility(), after.visibility());
		changed(changes, "status", before.status(), after.status());
		changed(changes, "guestName", before.guestName(), after.guestName());
		changed(changes, "blockReason", before.blockReason(), after.blockReason());
		var previousStaff = before.staff().stream().sorted(Comparator.comparing(AssignedStaffResponse::staffId)).toList();
		var currentStaff = after.staff().stream().sorted(Comparator.comparing(AssignedStaffResponse::staffId)).toList();
		if (!previousStaff.stream().map(member -> member.staffId() + ":" + member.role()).toList().equals(
				currentStaff.stream().map(member -> member.staffId() + ":" + member.role()).toList())) {
			changes.put("staff", new AuditLog.Change(previousStaff, currentStaff));
		}
		var previousRooms = before.resources().stream().sorted(Comparator.comparing(ScheduleDtos.AssignedResourceResponse::resourceId)).toList();
		var currentRooms = after.resources().stream().sorted(Comparator.comparing(ScheduleDtos.AssignedResourceResponse::resourceId)).toList();
		if (!previousRooms.stream().map(room -> room.resourceId() + ":" + room.blocksWholeRoom()).toList().equals(
				currentRooms.stream().map(room -> room.resourceId() + ":" + room.blocksWholeRoom()).toList())) {
			changes.put("resources", new AuditLog.Change(previousRooms, currentRooms));
		}
		return changes;
	}

	private static void changed(Map<String, AuditLog.Change> changes, String field, @Nullable Object before, @Nullable Object after) {
		if (!Objects.equals(before, after)) changes.put(field, new AuditLog.Change(before, after));
	}

	@Transactional(readOnly = true)
	List<Entry> history(UUID eventId) {
		var creation = this.jdbc.sql("SELECT created_at, created_by FROM schedule_event WHERE id=:event")
			.param("event", eventId).query((rs, row) -> new Entry("created-" + eventId,
					rs.getObject("created_at", OffsetDateTime.class).toInstant(), Kind.CREATED,
					rs.getObject("created_by", UUID.class), null, Map.of(), null)).optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
		List<AuditLog.Recorded> recorded = this.audit.history("scheduling", "schedule_event", List.of(eventId))
			.stream().filter(entry -> kind(entry.action()) != null).toList();
		var linkedRequests = recorded.stream().map(entry -> entry.changes().get("substitutionRequestId"))
			.filter(Objects::nonNull).map(change -> Objects.toString(change.newValue())).collect(Collectors.toSet());
		List<Entry> entries = new ArrayList<>(List.of(creation));
		for (AuditLog.Recorded entry : recorded) {
			Map<String, AuditLog.Change> changes = entry.changes().entrySet().stream()
				.filter(change -> FIELDS.contains(change.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			if (!changes.isEmpty()) {
				entries.add(new Entry("audit-" + entry.id(), entry.occurredAt(), Objects.requireNonNull(kind(entry.action())),
						entry.actorAccountId(), null, changes, entry.reason()));
			}
		}
		// Retain older approved substitutions; recipient notices do not reliably describe every occurrence or its author.
		var legacy = this.jdbc.sql("""
				SELECT id, original_staff_id, substitute_staff_id, decided_at, decided_by
				FROM substitution_request WHERE event_id=:event AND status='APPROVED'
				AND substitute_staff_id IS NOT NULL AND decided_at IS NOT NULL ORDER BY decided_at, id
				""").param("event", eventId).query((rs, row) -> new Legacy(rs.getObject("id", UUID.class),
					rs.getObject("original_staff_id", UUID.class), rs.getObject("substitute_staff_id", UUID.class),
					rs.getObject("decided_at", OffsetDateTime.class).toInstant(), rs.getObject("decided_by", UUID.class)))
			.list().stream().filter(entry -> !linkedRequests.contains(entry.id().toString())).toList();
		var members = this.staff.findAllById(legacy.stream().flatMap(entry ->
			java.util.stream.Stream.of(entry.original(), entry.substitute())).distinct().toList())
			.stream().collect(Collectors.toMap(StaffProfiles.StaffMember::id, StaffProfiles.StaffMember::displayName));
		for (Legacy entry : legacy) {
			entries.add(new Entry("substitution-" + entry.id(), entry.at(), Kind.SUBSTITUTION, entry.actor(), null,
					Map.of("staff", new AuditLog.Change(
						List.of(new AssignedStaffResponse(entry.original(), members.get(entry.original()), StaffRole.LEAD)),
						List.of(new AssignedStaffResponse(entry.substitute(), members.get(entry.substitute()), StaffRole.LEAD)))), null));
		}
		var actors = entries.stream().map(Entry::actorAccountId).filter(Objects::nonNull).distinct().toList();
		Map<UUID, String> names = new HashMap<>(this.customers.displayNamesByAccount(actors));
		names.putAll(this.staff.displayNamesByAccount(actors));
		return entries.stream().sorted(Comparator.comparing(Entry::occurredAt)
			.thenComparingLong(entry -> entry.id().startsWith("audit-") ? Long.parseLong(entry.id().substring(6)) : Long.MIN_VALUE)
			.thenComparing(Entry::id).reversed())
			.map(entry -> new Entry(entry.id(), entry.occurredAt(), entry.kind(), entry.actorAccountId(),
					entry.actorAccountId() == null ? null : names.get(entry.actorAccountId()), entry.changes(), entry.reason()))
			.toList();
	}

	private static @Nullable Kind kind(String action) {
		return switch (action) {
			case "EVENT_MANUAL", "STAFF_MANUAL" -> Kind.MANUAL;
			case "EVENT_SERIES_RESTORED", "STAFF_SERIES_RESTORED" -> Kind.SERIES_RESTORED;
			case "EVENT_SUBSTITUTION", "STAFF_SUBSTITUTION" -> Kind.SUBSTITUTION;
			case "EVENT_AUTOMATIC_SUBSTITUTION", "STAFF_AUTOMATIC_SUBSTITUTION" -> Kind.AUTOMATIC_SUBSTITUTION;
			case "EVENT_CANCELLED", "CANCELLED" -> Kind.CANCELLED;
			case "EVENT_COMPLETED" -> Kind.COMPLETED;
			case "EVENT_FORCE_RUN_CHANGED" -> Kind.FORCE_RUN_CHANGED;
			default -> null;
		};
	}

	private record Legacy(UUID id, UUID original, UUID substitute, Instant at, @Nullable UUID actor) {}
}
