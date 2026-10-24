package studioforyou.pl.backend.scheduling.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedResourceResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedStaffResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the schedule: events in a time window with their staff and resources, and the time
 * other events already take a staff member or a resource. Names of service types and staff come
 * from their owning modules.
 */
@Repository
class ScheduleQueries {

	private static final String EVENT_COLUMNS = """
			SELECT e.id, e.kind, e.status, e.visibility, e.title, e.service_type_id, e.series_id,
			       e.is_series_exception, e.training_group_id, e.starts_at, e.ends_at, e.capacity,
			       e.cancel_reason, e.block_reason, e.guest_name, e.version
			FROM schedule_event e
			""";

	private static final RowMapper<EventRow> EVENT_ROW = (rs, rowNum) -> new EventRow(
			rs.getObject("id", UUID.class), EventKind.valueOf(rs.getString("kind")),
			EventStatus.valueOf(rs.getString("status")), EventVisibility.valueOf(rs.getString("visibility")),
			rs.getString("title"), rs.getObject("service_type_id", UUID.class), rs.getObject("series_id", UUID.class),
			rs.getBoolean("is_series_exception"), rs.getObject("training_group_id", UUID.class),
			instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getObject("capacity", Integer.class),
			rs.getString("cancel_reason"), rs.getString("block_reason"), rs.getString("guest_name"),
			rs.getInt("version"));

	private final JdbcClient jdbc;

	private final ServiceTypes serviceTypes;

	private final StaffProfiles staffProfiles;

	ScheduleQueries(JdbcClient jdbc, ServiceTypes serviceTypes, StaffProfiles staffProfiles) {
		this.jdbc = jdbc;
		this.serviceTypes = serviceTypes;
		this.staffProfiles = staffProfiles;
	}

	/** Events overlapping the filter's window that match all its other criteria, earliest first. */
	List<ScheduleEventResponse> findEvents(TimetableFilter filter) {
		StringBuilder sql = new StringBuilder(EVENT_COLUMNS).append("WHERE e.starts_at < :to AND e.ends_at > :from ");
		Map<String, Object> params = new HashMap<>();
		params.put("from", utc(filter.from()));
		params.put("to", utc(filter.to()));
		in(sql, params, "e.kind", "kinds", names(filter.kinds()));
		in(sql, params, "e.status", "statuses", names(filter.statuses()));
		in(sql, params, "e.visibility", "visibilities", names(filter.visibilities()));
		in(sql, params, "e.service_type_id", "serviceTypeIds", filter.serviceTypeIds());
		in(sql, params, "e.training_group_id", "trainingGroupIds", filter.trainingGroupIds());
		in(sql, params, "e.series_id", "seriesIds", filter.seriesIds());
		if (!filter.staffIds().isEmpty()) {
			sql.append(" AND EXISTS (SELECT 1 FROM event_staff es")
				.append(" WHERE es.event_id = e.id AND es.staff_id IN (:staffIds))");
			params.put("staffIds", filter.staffIds());
		}
		if (!filter.resourceIds().isEmpty()) {
			sql.append(" AND EXISTS (SELECT 1 FROM event_resource er")
				.append(" WHERE er.event_id = e.id AND er.resource_id IN (:resourceIds))");
			params.put("resourceIds", filter.resourceIds());
		}
		sql.append(" ORDER BY e.starts_at, e.ends_at, e.id");
		return assemble(this.jdbc.sql(sql.toString()).params(params).query(EVENT_ROW).list());
	}

	Optional<ScheduleEventResponse> findEvent(UUID id) {
		List<EventRow> rows = this.jdbc.sql(EVENT_COLUMNS + "WHERE e.id = :id").param("id", id).query(EVENT_ROW).list();
		return assemble(rows).stream().findFirst();
	}

	long countSeriesOccurrences(UUID seriesId) {
		return this.jdbc.sql("SELECT count(*) FROM schedule_event WHERE series_id = :seriesId")
			.param("seriesId", seriesId)
			.query(Long.class)
			.single();
	}

	/**
	 * Uses of the given resources by events that are not cancelled and overlap {@code [from, to)}.
	 */
	List<ResourceUsage> findResourceUsage(Collection<UUID> resourceIds, Instant from, Instant to,
			Collection<UUID> ignoredEventIds) {
		if (resourceIds.isEmpty()) {
			return List.of();
		}
		Map<String, Object> params = usageParams(from, to, ignoredEventIds);
		params.put("resourceIds", resourceIds);
		String sql = """
				SELECT e.id, e.title, e.starts_at, e.ends_at, er.resource_id, er.blocks_whole_room
				FROM event_resource er
				JOIN schedule_event e ON e.id = er.event_id
				WHERE er.resource_id IN (:resourceIds)
				  AND e.status <> 'CANCELLED' AND e.starts_at < :to AND e.ends_at > :from
				""" + ignoredClause(ignoredEventIds);
		return this.jdbc.sql(sql)
			.params(params)
			.query((rs, rowNum) -> new ResourceUsage(rs.getObject("id", UUID.class), rs.getString("title"),
					instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getObject("resource_id", UUID.class),
					rs.getBoolean("blocks_whole_room")))
			.list();
	}

	/**
	 * Assignments of the given staff members to events that are not cancelled and overlap
	 * {@code [from, to)}.
	 */
	List<StaffUsage> findStaffUsage(Collection<UUID> staffIds, Instant from, Instant to,
			Collection<UUID> ignoredEventIds) {
		if (staffIds.isEmpty()) {
			return List.of();
		}
		Map<String, Object> params = usageParams(from, to, ignoredEventIds);
		params.put("staffIds", staffIds);
		String sql = """
				SELECT e.id, e.title, e.starts_at, e.ends_at, es.staff_id
				FROM event_staff es
				JOIN schedule_event e ON e.id = es.event_id
				WHERE es.staff_id IN (:staffIds)
				  AND e.status <> 'CANCELLED' AND e.starts_at < :to AND e.ends_at > :from
				""" + ignoredClause(ignoredEventIds);
		return this.jdbc.sql(sql)
			.params(params)
			.query((rs, rowNum) -> new StaffUsage(rs.getObject("id", UUID.class), rs.getString("title"),
					instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getObject("staff_id", UUID.class)))
			.list();
	}

	private List<ScheduleEventResponse> assemble(List<EventRow> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = rows.stream().map(EventRow::id).toList();
		Map<UUID, List<StaffRow>> staffByEvent = this.jdbc
			.sql("SELECT event_id, staff_id, role FROM event_staff WHERE event_id IN (:ids)")
			.param("ids", ids)
			.query((rs, rowNum) -> new StaffRow(rs.getObject("event_id", UUID.class),
					rs.getObject("staff_id", UUID.class), StaffRole.valueOf(rs.getString("role"))))
			.list()
			.stream()
			.collect(Collectors.groupingBy(StaffRow::eventId));
		Map<UUID, List<AssignedResourceRow>> resourcesByEvent = this.jdbc.sql("""
				SELECT er.event_id, er.resource_id, er.blocks_whole_room, r.name, r.kind
				FROM event_resource er
				JOIN resource r ON r.id = er.resource_id
				WHERE er.event_id IN (:ids)
				""")
			.param("ids", ids)
			.query((rs, rowNum) -> new AssignedResourceRow(rs.getObject("event_id", UUID.class),
					new AssignedResourceResponse(rs.getObject("resource_id", UUID.class), rs.getString("name"),
							ResourceKind.valueOf(rs.getString("kind")), rs.getBoolean("blocks_whole_room"))))
			.list()
			.stream()
			.collect(Collectors.groupingBy(AssignedResourceRow::eventId));

		Map<UUID, String> staffNames = this.staffProfiles
			.findAllById(staffByEvent.values().stream().flatMap(List::stream).map(StaffRow::staffId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(StaffMember::id, StaffMember::displayName));
		Map<UUID, String> serviceTypeNames = this.serviceTypes
			.findAllById(rows.stream().map(EventRow::serviceTypeId).filter((id) -> id != null).distinct().toList())
			.stream()
			.collect(Collectors.toMap(ServiceTypeSummary::id, ServiceTypeSummary::name));

		Map<UUID, ScheduleEventResponse> result = new LinkedHashMap<>();
		for (EventRow row : rows) {
			List<AssignedStaffResponse> staff = staffByEvent.getOrDefault(row.id(), List.of())
				.stream()
				.map((s) -> new AssignedStaffResponse(s.staffId(), staffNames.get(s.staffId()), s.role()))
				.sorted(Comparator.comparing(AssignedStaffResponse::role)
					.thenComparing(AssignedStaffResponse::displayName, Comparator.nullsLast(String::compareTo)))
				.toList();
			List<AssignedResourceResponse> resources = resourcesByEvent.getOrDefault(row.id(), List.of())
				.stream()
				.map(AssignedResourceRow::resource)
				.sorted(Comparator.comparing(AssignedResourceResponse::kind)
					.thenComparing(AssignedResourceResponse::name))
				.toList();
			result.put(row.id(), row.toResponse(serviceTypeNames.get(row.serviceTypeId()), staff, resources));
		}
		return new ArrayList<>(result.values());
	}

	private static Map<String, Object> usageParams(Instant from, Instant to, Collection<UUID> ignoredEventIds) {
		Map<String, Object> params = new HashMap<>();
		params.put("from", utc(from));
		params.put("to", utc(to));
		if (!ignoredEventIds.isEmpty()) {
			params.put("ignored", ignoredEventIds);
		}
		return params;
	}

	private static String ignoredClause(Collection<UUID> ignoredEventIds) {
		return ignoredEventIds.isEmpty() ? "" : " AND e.id NOT IN (:ignored)";
	}

	private static void in(StringBuilder sql, Map<String, Object> params, String column, String name,
			Collection<?> values) {
		if (!values.isEmpty()) {
			sql.append(" AND ").append(column).append(" IN (:").append(name).append(')');
			params.put(name, values);
		}
	}

	private static List<String> names(Collection<? extends Enum<?>> values) {
		return values.stream().map(Enum::name).toList();
	}

	static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, OffsetDateTime.class).toInstant();
	}

	/**
	 * The criteria of a timetable query. An empty list means no restriction.
	 */
	record TimetableFilter(Instant from, Instant to, List<EventKind> kinds, List<EventStatus> statuses,
			List<EventVisibility> visibilities, List<UUID> serviceTypeIds, List<UUID> staffIds,
			List<UUID> resourceIds, List<UUID> trainingGroupIds, List<UUID> seriesIds) {

		TimetableFilter {
			kinds = orEmpty(kinds);
			statuses = orEmpty(statuses);
			visibilities = orEmpty(visibilities);
			serviceTypeIds = orEmpty(serviceTypeIds);
			staffIds = orEmpty(staffIds);
			resourceIds = orEmpty(resourceIds);
			trainingGroupIds = orEmpty(trainingGroupIds);
			seriesIds = orEmpty(seriesIds);
		}

		private static <T> List<T> orEmpty(@Nullable List<T> values) {
			return (values != null) ? values.stream().distinct().toList() : List.of();
		}

	}

	/** An event that takes a resource. */
	record ResourceUsage(UUID eventId, @Nullable String title, Instant startsAt, Instant endsAt, UUID resourceId,
			boolean blocksWholeRoom) {
	}

	/** An event a staff member is assigned to. */
	record StaffUsage(UUID eventId, @Nullable String title, Instant startsAt, Instant endsAt, UUID staffId) {
	}

	private record EventRow(UUID id, EventKind kind, EventStatus status, EventVisibility visibility, String title,
			UUID serviceTypeId, UUID seriesId, boolean seriesException, UUID trainingGroupId, Instant startsAt,
			Instant endsAt, Integer capacity, String cancelReason, String blockReason, String guestName,
			int version) {

		ScheduleEventResponse toResponse(String serviceTypeName, List<AssignedStaffResponse> staff,
				List<AssignedResourceResponse> resources) {
			return new ScheduleEventResponse(this.id, this.kind, this.status, this.visibility, this.title,
					this.serviceTypeId, serviceTypeName, this.seriesId, this.seriesException, this.trainingGroupId,
					this.startsAt, this.endsAt, this.capacity, staff, resources, this.cancelReason, this.blockReason,
					this.guestName, this.version);
		}

	}

	private record StaffRow(UUID eventId, UUID staffId, StaffRole role) {
	}

	private record AssignedResourceRow(UUID eventId, AssignedResourceResponse resource) {
	}

}
