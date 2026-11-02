package studioforyou.pl.backend.scheduling.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.EventParticipantCounts;
import studioforyou.pl.backend.scheduling.ScheduleEventMoved;
import studioforyou.pl.backend.scheduling.internal.ScheduleConflicts.Assignments;
import studioforyou.pl.backend.scheduling.internal.ScheduleConflicts.Occurrence;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ChangeEventRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.OccurrencePreview;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.PlanPreviewResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ResourceAssignmentRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.RestoreSeriesRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleIssue;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.StaffAssignmentRequest;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes one event without touching the rest of its series: its time, title, capacity, staff and
 * rooms. A changed occurrence of a series becomes an exception to the series' rule, and can later
 * be brought back to the rule. The same checks as for a new event apply: the event must start in
 * the future and its rooms and staff must be free at the new time. The capacity may not drop below
 * the places already booked.
 */
@Service
@Transactional
class OccurrenceChanges {

	private final ScheduleEventRepository events;

	private final RecurrenceSeriesRepository seriesRepository;

	private final SchedulePlanner planner;

	private final ScheduleConflicts conflicts;

	private final ScheduleQueries queries;

	private final ServiceTypes serviceTypes;

	private final EventParticipantCounts participants;

	private final StudioSettings studio;

	private final ApplicationEventPublisher publisher;

	private final Clock clock = Clock.systemUTC();

	OccurrenceChanges(ScheduleEventRepository events, RecurrenceSeriesRepository seriesRepository,
			SchedulePlanner planner, ScheduleConflicts conflicts, ScheduleQueries queries, ServiceTypes serviceTypes,
			EventParticipantCounts participants, StudioSettings studio, ApplicationEventPublisher publisher) {
		this.events = events;
		this.seriesRepository = seriesRepository;
		this.planner = planner;
		this.conflicts = conflicts;
		this.queries = queries;
		this.serviceTypes = serviceTypes;
		this.participants = participants;
		this.studio = studio;
		this.publisher = publisher;
	}

	/**
	 * Lists the conflicts and warnings the change would have, without saving anything.
	 */
	@Transactional(readOnly = true)
	PlanPreviewResponse preview(UUID eventId, ChangeEventRequest request) {
		ScheduleEvent event = this.events.findById(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
		requireEditable(event);
		ZoneId zone = this.studio.zone();
		Occurrence occurrence = this.planner.checkedOccurrence(request.startsAt(), request.endsAt(), zone);
		Short capacity = capacity(request.capacity());
		Assignments assignments = this.planner.assignments(event.getServiceTypeId(), toInteger(capacity),
				request.staff(), request.resources(), event);
		List<ScheduleIssue> issues = this.conflicts.find(List.of(occurrence), assignments, zone, List.of(eventId))
			.get(0);
		OccurrencePreview preview = new OccurrencePreview(occurrence.date(), occurrence.startsAt(),
				occurrence.endsAt(), false, issues);
		return new PlanPreviewResponse(List.of(preview),
				this.conflicts.warnings(event.getServiceTypeId(), assignments), preview.hasBlockingIssue() ? 1 : 0);
	}

	/**
	 * Applies the change to the event only. A change of an occurrence of a series marks it as an
	 * exception; a request that changes nothing leaves the event as it is.
	 */
	ScheduleEventResponse change(UUID eventId, ChangeEventRequest request) {
		ScheduleEvent event = lockForChange(eventId, request.version());
		ZoneId zone = this.studio.zone();
		Occurrence occurrence = this.planner.checkedOccurrence(request.startsAt(), request.endsAt(), zone);
		Short capacity = capacity(request.capacity());
		requireRoomForParticipants(event, capacity);
		Assignments assignments = this.planner.assignments(event.getServiceTypeId(), toInteger(capacity),
				request.staff(), request.resources(), event);
		this.planner.lockAndRejectConflicts(List.of(occurrence), assignments, zone, List.of(eventId));

		Instant previousStart = event.getStartsAt();
		Instant previousEnd = event.getEndsAt();
		boolean moved = event.reschedule(occurrence.startsAt(), occurrence.endsAt());
		boolean detailsChanged = event.changeDetails(title(request.title(), event), capacity);
		boolean assignmentsChanged = event.replaceAssignments(assignments.staff(), assignments.resources());
		if (moved || detailsChanged || assignmentsChanged) {
			event.markSeriesException();
		}
		this.events.flush();
		if (moved) {
			this.publisher.publishEvent(new ScheduleEventMoved(eventId, previousStart, previousEnd,
					event.getStartsAt(), event.getEndsAt()));
		}
		return this.queries.findEvent(eventId).orElseThrow();
	}

	/**
	 * Brings an occurrence that was changed back to its series' rule: the time the rule gives it,
	 * the series' title and capacity, and the staff and rooms of the closest occurrence that still
	 * follows the rule (the series' default lead and room when there is none).
	 */
	ScheduleEventResponse restoreSeries(UUID eventId, RestoreSeriesRequest request) {
		ScheduleEvent event = lockForChange(eventId, request.version());
		if (event.getSeriesId() == null) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE, "A one-off event has no series to restore.");
		}
		if (!event.isSeriesException()) {
			return this.queries.findEvent(eventId).orElseThrow();
		}
		RecurrenceSeries series = this.seriesRepository.findById(event.getSeriesId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Recurring series not found."));
		Instant startsAt = event.getSeriesOriginalStart();
		if (!startsAt.isAfter(this.clock.instant())) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE,
					"The series' time of this occurrence has already passed.");
		}
		Instant endsAt = startsAt.plus(Duration.ofMinutes(series.getDurationMinutes()));
		ZoneId zone = this.studio.zone();
		Occurrence occurrence = this.planner.checkedOccurrence(startsAt, endsAt, zone);
		Short capacity = series.getCapacity();
		requireRoomForParticipants(event, capacity);

		ScheduleEvent reference = this.events.findSeriesRuleOccurrences(series.getId(), eventId)
			.stream()
			.min(Comparator.comparing((ScheduleEvent other) -> Duration.between(other.getStartsAt(), startsAt).abs()))
			.orElse(null);
		List<StaffAssignmentRequest> staff = (reference != null) ? reference.getStaff()
			.stream()
			.map((assignment) -> new StaffAssignmentRequest(assignment.staffId(), assignment.role()))
			.toList() : defaultStaff(series);
		List<ResourceAssignmentRequest> resources = (reference != null) ? reference.getResources()
			.stream()
			.map((assignment) -> new ResourceAssignmentRequest(assignment.resourceId(), assignment.blocksWholeRoom()))
			.toList() : defaultResources(series);
		Assignments assignments = this.planner.assignments(event.getServiceTypeId(), toInteger(capacity), staff,
				resources, event);
		this.planner.lockAndRejectConflicts(List.of(occurrence), assignments, zone, List.of(eventId));

		Instant previousStart = event.getStartsAt();
		Instant previousEnd = event.getEndsAt();
		boolean moved = event.reschedule(occurrence.startsAt(), occurrence.endsAt());
		event.changeDetails(title(series.getTitle(), event), capacity);
		event.replaceAssignments(assignments.staff(), assignments.resources());
		event.clearSeriesException();
		this.events.flush();
		if (moved) {
			this.publisher.publishEvent(new ScheduleEventMoved(eventId, previousStart, previousEnd,
					event.getStartsAt(), event.getEndsAt()));
		}
		return this.queries.findEvent(eventId).orElseThrow();
	}

	private ScheduleEvent lockForChange(UUID eventId, int version) {
		ScheduleEvent event = this.events.findByIdForUpdate(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
		if (event.getVersion() != version) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT,
					"The event was modified by another request. Reload it and try again.");
		}
		requireEditable(event);
		return event;
	}

	/** Only a scheduled class, workshop or personal training that has not started yet can change. */
	private void requireEditable(ScheduleEvent event) {
		if (event.getStatus() != EventStatus.SCHEDULED) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE,
					"Only a scheduled event can be changed; this one is " + event.getStatus() + ".");
		}
		if (!EventKind.PLANNABLE.contains(event.getKind())) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE,
					"Events of the kind " + event.getKind() + " cannot be changed here.");
		}
		if (!event.getStartsAt().isAfter(this.clock.instant())) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE, "The event has already started.");
		}
	}

	private void requireRoomForParticipants(ScheduleEvent event, @Nullable Short capacity) {
		if (capacity == null) {
			return;
		}
		int booked = this.participants.activeParticipants(event.getId());
		if (capacity < booked) {
			throw ApiException.invalidField("capacity", FieldErrorCode.OUT_OF_RANGE,
					"The event already has " + booked + " booked places; the capacity cannot be lower.");
		}
	}

	/** The requested title, or the service type's name when it is blank. */
	private String title(@Nullable String requested, ScheduleEvent event) {
		String title = (requested != null && !requested.isBlank()) ? requested.strip()
				: this.serviceTypes.findById(event.getServiceTypeId())
					.map(ServiceTypeSummary::name)
					.orElse(event.getTitle());
		if (title != null && title.length() > ScheduleDtos.MAX_TITLE_LENGTH) {
			title = title.substring(0, ScheduleDtos.MAX_TITLE_LENGTH);
		}
		return title;
	}

	private static @Nullable Short capacity(@Nullable Integer requested) {
		return (requested != null) ? requested.shortValue() : null;
	}

	private static @Nullable Integer toInteger(@Nullable Short capacity) {
		return (capacity != null) ? Integer.valueOf(capacity) : null;
	}

	private static List<StaffAssignmentRequest> defaultStaff(RecurrenceSeries series) {
		return (series.getDefaultStaffId() != null)
				? List.of(new StaffAssignmentRequest(series.getDefaultStaffId(), StaffRole.LEAD)) : List.of();
	}

	private static List<ResourceAssignmentRequest> defaultResources(RecurrenceSeries series) {
		return (series.getDefaultResourceId() != null)
				? List.of(new ResourceAssignmentRequest(series.getDefaultResourceId(), true)) : List.of();
	}

}
