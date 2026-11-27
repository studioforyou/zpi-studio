package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.scheduling.CalendarEvents;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedResourceResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedStaffResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;

@Service
@Transactional(readOnly = true)
class DefaultCalendarEvents implements CalendarEvents {

	private final ScheduleQueries queries;

	private final JdbcClient jdbc;

	DefaultCalendarEvents(ScheduleQueries queries, JdbcClient jdbc) {
		this.queries = queries;
		this.jdbc = jdbc;
	}

	@Override
	public List<CalendarEvent> findAllById(Collection<UUID> ids) {
		return calendarEvents(this.queries.findEventsById(List.copyOf(ids)));
	}

	@Override
	public List<CalendarEvent> findAssignedTo(UUID staffId, Instant endingAfter) {
		List<UUID> ids = this.jdbc.sql("""
				SELECT e.id
				FROM schedule_event e
				JOIN event_staff s ON s.event_id = e.id
				WHERE s.staff_id = :staffId AND e.kind <> 'BLOCK' AND e.ends_at > :endingAfter
				""")
			.param("staffId", staffId)
			.param("endingAfter", ScheduleQueries.utc(endingAfter))
			.query(UUID.class)
			.list();
		return calendarEvents(this.queries.findEventsById(ids));
	}

	private static List<CalendarEvent> calendarEvents(List<ScheduleEventResponse> events) {
		return events.stream()
			.filter((event) -> event.kind() != EventKind.BLOCK)
			.sorted(Comparator.comparing(ScheduleEventResponse::startsAt).thenComparing(ScheduleEventResponse::id))
			.map(DefaultCalendarEvents::calendarEvent)
			.toList();
	}

	private static CalendarEvent calendarEvent(ScheduleEventResponse event) {
		List<String> leads = event.staff().stream()
			.filter((staff) -> staff.role() == StaffRole.LEAD)
			.map(AssignedStaffResponse::displayName)
			.filter((name) -> name != null && !name.isBlank())
			.toList();
		List<String> rooms = event.resources().stream().map(AssignedResourceResponse::name).toList();
		return new CalendarEvent(event.id(), event.kind().name(), event.status().name(), event.title(),
				event.serviceTypeName(), event.startsAt(), event.endsAt(), leads, rooms, event.version());
	}

}
