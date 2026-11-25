package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.PersonalTrainingPlanning;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.CreateEventRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ResourceAssignmentRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.StaffAssignmentRequest;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

@Service
@Transactional(readOnly = true)
class DefaultPersonalTrainingPlanning implements PersonalTrainingPlanning {

	private final SchedulePlanner planner;
	private final ServiceTypes services;
	private final JdbcClient jdbc;

	DefaultPersonalTrainingPlanning(SchedulePlanner planner, ServiceTypes services, JdbcClient jdbc) {
		this.planner = planner;
		this.services = services;
		this.jdbc = jdbc;
	}

	@Override
	public List<Issue> preview(Plan plan, UUID staffId) {
		var preview = planner.previewEvent(request(plan, staffId, null));
		return Stream.concat(preview.occurrences().stream().flatMap(o -> o.issues().stream()), preview.warnings().stream())
			.map(i -> new Issue(i.type().name(), i.blocking(), i.eventTitle(), i.startsAt(), i.endsAt())).toList();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID create(Plan plan, UUID staffId, UUID actorId, String participantName) {
		return planner.createEvent(request(plan, staffId, participantName), actorId).id();
	}

	@Override
	public boolean overlaps(List<UUID> eventIds, Instant startsAt, Instant endsAt) {
		if (eventIds.isEmpty()) return false;
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM schedule_event WHERE id IN (:ids) AND status <> 'CANCELLED'
				               AND starts_at < :endsAt AND ends_at > :startsAt)
				""").param("ids", eventIds).param("startsAt", OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC))
			.param("endsAt", OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC)).query(Boolean.class).single();
	}

	private CreateEventRequest request(Plan plan, UUID staffId, @Nullable String participantName) {
		if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM resource WHERE id = :id AND kind = 'ROOM' AND is_active)")
				.param("id", plan.resourceId()).query(Boolean.class).single()) {
			throw new ApiException(ErrorCode.RESOURCE_UNAVAILABLE, "Choose an active room.");
		}
		var service = services.findById(plan.serviceTypeId())
			.filter(s -> s.active() && "PERSONAL_TRAINING".equals(s.category()))
			.orElseThrow(() -> new ApiException(ErrorCode.SERVICE_TYPE_UNAVAILABLE, "Choose an active personal training service."));
		String title = service.name() + (participantName == null ? "" : " — " + participantName);
		return new CreateEventRequest(EventKind.PERSONAL_TRAINING, plan.serviceTypeId(), title, 1,
				EventVisibility.PRIVATE, plan.startsAt(), plan.endsAt(),
				List.of(new StaffAssignmentRequest(staffId, StaffRole.LEAD)),
				List.of(new ResourceAssignmentRequest(plan.resourceId(), true)), null);
	}
}
