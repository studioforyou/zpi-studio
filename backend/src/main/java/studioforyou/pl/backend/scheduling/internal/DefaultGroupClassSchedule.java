package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
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
		List<ScheduleEventResponse> events = queries.findEvents(new TimetableFilter(from, to, List.of(EventKind.CLASS), null,
				List.of(EventVisibility.PUBLIC), null, null, null, null, null))
			.stream().filter(e -> e.serviceTypeId() != null).toList();
		Map<UUID, ServiceTypeSummary> definitions = services
			.findAllById(events.stream().map(ScheduleEventResponse::serviceTypeId).distinct().toList())
			.stream().collect(Collectors.toMap(ServiceTypeSummary::id, definition -> definition));
		return events.stream().map(event -> {
			ServiceTypeSummary definition = definitions.get(event.serviceTypeId());
			return occurrence(event, definition == null ? null : definition.calendarColor());
		}).toList();
	}

	@Override
	public Optional<Occurrence> find(UUID id) {
		return queries.findEvent(id)
			.filter(e -> e.kind() == EventKind.CLASS && e.visibility() == EventVisibility.PUBLIC && e.serviceTypeId() != null)
			.map(event -> occurrence(event, services.findById(event.serviceTypeId())
				.map(ServiceTypeSummary::calendarColor).orElse(null)));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Occurrence lock(UUID id) {
		jdbc.sql("SELECT id FROM schedule_event WHERE id = :id AND kind = 'CLASS' AND visibility = 'PUBLIC' AND service_type_id IS NOT NULL FOR UPDATE")
			.param("id", id).query(UUID.class).optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Group class not found."));
		return find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Group class not found."));
	}

	private static Occurrence occurrence(ScheduleEventResponse event, String calendarColor) {
		return new Occurrence(event.id(), event.title(), event.serviceTypeId(), event.serviceTypeName(),
				calendarColor, event.trainingGroupId(), event.startsAt(), event.endsAt(), event.status().name(), event.capacity(),
				event.staff().stream().map(s -> new Instructor(s.staffId(), s.displayName(), s.role().name())).toList(),
				event.resources().stream().map(r -> new Room(r.resourceId(), r.name())).toList(), event.version());
	}

}
