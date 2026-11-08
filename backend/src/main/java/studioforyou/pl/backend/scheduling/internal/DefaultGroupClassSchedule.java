package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleQueries.TimetableFilter;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

@Service
@Transactional(readOnly = true)
class DefaultGroupClassSchedule implements GroupClassSchedule {

	private final ScheduleQueries queries;

	private final JdbcClient jdbc;

	private final ServiceTypes services;

	DefaultGroupClassSchedule(ScheduleQueries queries, JdbcClient jdbc, ServiceTypes services) {
		this.queries = queries;
		this.jdbc = jdbc;
		this.services = services;
	}

	@Override
	public List<Occurrence> list(Instant from, Instant to) {
		return classes(from, to, List.of(EventVisibility.PUBLIC));
	}

	@Override
	public List<Occurrence> listIncludingPrivate(Instant from, Instant to) {
		return classes(from, to, List.of(EventVisibility.PUBLIC, EventVisibility.PRIVATE));
	}

	@Override
	public List<Occurrence> findStartingBetween(Collection<UUID> ids, Instant from, Instant to) {
		return occurrences(queries.findEventsStartingBetween(ids, from, to));
	}

	@Override
	public Optional<Occurrence> find(UUID id) {
		return findClass(id, true);
	}

	@Override
	public Optional<Occurrence> findIncludingPrivate(UUID id) {
		return findClass(id, false);
	}

	@Override
	public List<Occurrence> findAllById(Collection<UUID> ids) {
		return occurrences(queries.findEventsById(ids));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Occurrence lock(UUID id) {
		return lock(id, "AND visibility = 'PUBLIC'");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Occurrence lockIncludingPrivate(UUID id) {
		return lock(id, "");
	}

	private Optional<Occurrence> findClass(UUID id, boolean onlyPublic) {
		return queries.findEvent(id)
			.filter(e -> e.kind() == EventKind.CLASS && e.serviceTypeId() != null
					&& (!onlyPublic || e.visibility() == EventVisibility.PUBLIC))
			.map(event -> occurrence(event, services.findById(event.serviceTypeId())
				.map(ServiceTypeSummary::calendarColor).orElse(null)));
	}

	private Occurrence lock(UUID id, String visibility) {
		jdbc.sql("SELECT id FROM schedule_event WHERE id = :id AND kind = 'CLASS' " + visibility
				+ " AND service_type_id IS NOT NULL FOR UPDATE")
			.param("id", id).query(UUID.class).optional()
			.orElseThrow(DefaultGroupClassSchedule::notFound);
		return findIncludingPrivate(id).orElseThrow(DefaultGroupClassSchedule::notFound);
	}

	private List<Occurrence> classes(Instant from, Instant to, List<EventVisibility> visibilities) {
		return occurrences(queries.findEvents(new TimetableFilter(from, to, List.of(EventKind.CLASS), null,
				visibilities, null, null, null, null, null))
			.stream().filter(e -> e.serviceTypeId() != null).toList());
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Group class not found.");
	}

	private List<Occurrence> occurrences(List<ScheduleEventResponse> events) {
		Map<UUID, ServiceTypeSummary> definitions = services
			.findAllById(events.stream().map(ScheduleEventResponse::serviceTypeId).filter(id -> id != null).distinct().toList())
			.stream().collect(Collectors.toMap(ServiceTypeSummary::id, definition -> definition));
		return events.stream().map(event -> {
			ServiceTypeSummary definition = definitions.get(event.serviceTypeId());
			return occurrence(event, definition == null ? null : definition.calendarColor());
		}).toList();
	}

	private static Occurrence occurrence(ScheduleEventResponse event, String calendarColor) {
		return new Occurrence(event.id(), event.title(), event.serviceTypeId(), event.serviceTypeName(),
				calendarColor, event.trainingGroupId(), event.startsAt(), event.endsAt(), event.status().name(), event.capacity(),
				event.staff().stream().map(s -> new Instructor(s.staffId(), s.displayName(), s.role().name())).toList(),
				event.resources().stream().map(r -> new Room(r.resourceId(), r.name())).toList(), event.version());
	}

}
