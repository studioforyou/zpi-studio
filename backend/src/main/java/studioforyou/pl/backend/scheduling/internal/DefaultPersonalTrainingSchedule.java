package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.PersonalTrainingSchedule;

@Service
@Transactional(readOnly = true)
class DefaultPersonalTrainingSchedule implements PersonalTrainingSchedule {

	private final ScheduleQueries queries;

	DefaultPersonalTrainingSchedule(ScheduleQueries queries) {
		this.queries = queries;
	}

	@Override
	public List<GroupClassSchedule.Occurrence> findOwn(Collection<UUID> eventIds, UUID staffId,
			Instant from, Instant to) {
		return queries.findOwnPersonalEvents(eventIds, staffId, from, to).stream()
			.map(event -> new GroupClassSchedule.Occurrence(event.id(), event.title(), event.serviceTypeId(),
					event.serviceTypeName(), null, event.trainingGroupId(), event.startsAt(), event.endsAt(),
					event.status().name(), event.capacity(),
					event.staff().stream().map(s -> new GroupClassSchedule.Instructor(s.staffId(), s.displayName(),
							s.role().name())).toList(),
					event.resources().stream().map(r -> new GroupClassSchedule.Room(r.resourceId(), r.name())).toList(),
					event.version()))
			.toList();
	}

}
