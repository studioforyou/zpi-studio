package studioforyou.pl.backend.scheduling.internal;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.internal.ScheduleConflicts.Assignments;
import studioforyou.pl.backend.scheduling.internal.ScheduleConflicts.Occurrence;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignmentScope;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ChangeAssignmentsRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.CreateEventRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.CreateSeriesRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.EventDetailsRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.IssueType;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.OccurrencePreview;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.PlanPreviewResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ResourceAssignmentRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleIssue;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.SeriesPlanResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.SeriesResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.StaffAssignmentRequest;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans the studio's offer: creates one-off events and recurring series with their staff and
 * rooms, and changes those assignments later. A plan is rejected when a room or a staff
 * member is already taken at one of its times; a preview shows such conflicts in advance.
 * Times of a series are in the studio's local time, so a class stays at 18:00 across a
 * daylight saving time change.
 */
@Service
@Transactional
class SchedulePlanner {

	/** The longest a one-off event may last. */
	static final Duration MAX_EVENT_DURATION = Duration.ofHours(24);

	private final ScheduleEventRepository events;

	private final RecurrenceSeriesRepository seriesRepository;

	private final ResourceRepository resources;

	private final ServiceTypeResourceRepository roomRules;

	private final ScheduleQueries queries;

	private final ScheduleConflicts conflicts;

	private final ScheduleLocks locks;

	private final ServiceTypes serviceTypes;

	private final StaffProfiles staffProfiles;

	private final StudioSettings studio;

	private final Clock clock = Clock.systemUTC();

	SchedulePlanner(ScheduleEventRepository events, RecurrenceSeriesRepository seriesRepository,
			ResourceRepository resources, ServiceTypeResourceRepository roomRules, ScheduleQueries queries,
			ScheduleConflicts conflicts, ScheduleLocks locks, ServiceTypes serviceTypes, StaffProfiles staffProfiles,
			StudioSettings studio) {
		this.events = events;
		this.seriesRepository = seriesRepository;
		this.resources = resources;
		this.roomRules = roomRules;
		this.queries = queries;
		this.conflicts = conflicts;
		this.locks = locks;
		this.serviceTypes = serviceTypes;
		this.staffProfiles = staffProfiles;
		this.studio = studio;
	}

	@Transactional(readOnly = true)
	PlanPreviewResponse previewEvent(CreateEventRequest request) {
		ZoneId zone = this.studio.zone();
		Details details = details(request.details());
		return preview(details, List.of(occurrence(request, zone)), zone);
	}

	ScheduleEventResponse createEvent(CreateEventRequest request, UUID actorAccountId) {
		ZoneId zone = this.studio.zone();
		Details details = details(request.details());
		Occurrence occurrence = occurrence(request, zone);
		lockAndRejectConflicts(List.of(occurrence), details.assignments(), zone, Set.of());

		ScheduleEvent event = newEvent(details, null, occurrence, actorAccountId);
		this.events.save(event);
		this.events.flush();
		return this.queries.findEvent(event.getId()).orElseThrow();
	}

	@Transactional(readOnly = true)
	PlanPreviewResponse previewSeries(CreateSeriesRequest request) {
		ZoneId zone = this.studio.zone();
		Details details = details(request.details());
		return preview(details, occurrences(request, zone), zone);
	}

	SeriesPlanResponse createSeries(CreateSeriesRequest request, UUID actorAccountId) {
		ZoneId zone = this.studio.zone();
		Details details = details(request.details());
		List<Occurrence> planned = occurrences(request, zone).stream().filter((o) -> !o.excluded()).toList();
		lockAndRejectConflicts(planned, details.assignments(), zone, Set.of());

		int intervalWeeks = intervalWeeks(request);
		Map<Integer, List<Occurrence>> byWeekday = planned.stream()
			.collect(Collectors.groupingBy((o) -> o.date().getDayOfWeek().getValue(), LinkedHashMap::new,
					Collectors.toList()));
		List<SeriesResponse> created = new ArrayList<>();
		byWeekday.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach((entry) -> {
			List<Occurrence> occurrences = entry.getValue();
			RecurrenceSeries series = new RecurrenceSeries(details.serviceType().id(), details.title(), entry.getKey(),
					request.localStartTime(), request.durationMinutes(), intervalWeeks, occurrences.get(0).date(),
					request.endsOn(), details.capacity(), actorAccountId);
			series.setDefaults(details.assignments().leadStaffId(), details.assignments().mainResourceId());
			this.seriesRepository.save(series);
			for (Occurrence occurrence : occurrences) {
				this.events.save(newEvent(details, series.getId(), occurrence, actorAccountId));
			}
			created.add(SeriesResponse.from(series, occurrences.size()));
		});
		this.events.flush();
		return new SeriesPlanResponse(created, planned.size());
	}

	@Transactional(readOnly = true)
	SeriesResponse getSeries(UUID seriesId) {
		RecurrenceSeries series = this.seriesRepository.findById(seriesId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Recurring series not found."));
		return SeriesResponse.from(series, this.queries.countSeriesOccurrences(seriesId));
	}

	/**
	 * Replaces the staff and rooms of an event, or of the event and the later scheduled
	 * occurrences of its series. A change of one occurrence marks it as an exception to its series.
	 */
	ScheduleEventResponse changeAssignments(UUID eventId, ChangeAssignmentsRequest request) {
		ScheduleEvent event = this.events.findByIdForUpdate(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
		if (event.getVersion() != request.version()) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT,
					"The event was modified by another request. Reload it and try again.");
		}
		if (event.getStatus() != EventStatus.SCHEDULED) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE,
					"Only a scheduled event can get other staff or resources; this one is " + event.getStatus() + ".");
		}
		boolean following = request.scope() == AssignmentScope.THIS_AND_FOLLOWING && event.getSeriesId() != null;
		List<ScheduleEvent> targets = following ? this.events.findSeriesOccurrencesForUpdate(event.getSeriesId(),
				event.getStartsAt(), EventStatus.SCHEDULED) : List.of(event);

		Short capacity = event.getCapacity();
		Assignments assignments = assignments(event.getServiceTypeId(), (capacity != null) ? capacity.intValue() : null,
				request.staff(), request.resources(), event);
		ZoneId zone = this.studio.zone();
		List<Occurrence> occurrences = targets.stream()
			.map((target) -> new Occurrence(LocalDate.ofInstant(target.getStartsAt(), zone), target.getStartsAt(),
					target.getEndsAt(), false))
			.toList();
		lockAndRejectConflicts(occurrences, assignments, zone, targets.stream().map(ScheduleEvent::getId).toList());

		for (ScheduleEvent target : targets) {
			boolean changed = target.replaceAssignments(assignments.staff(), assignments.resources());
			if (changed && !following) {
				target.markSeriesException();
			}
		}
		if (following) {
			this.seriesRepository.findById(event.getSeriesId())
				.ifPresent((series) -> series.setDefaults(assignments.leadStaffId(), assignments.mainResourceId()));
		}
		this.events.flush();
		return this.queries.findEvent(eventId).orElseThrow();
	}

	private PlanPreviewResponse preview(Details details, List<Occurrence> occurrences, ZoneId zone) {
		List<List<ScheduleIssue>> issues = this.conflicts.find(occurrences, details.assignments(), zone, Set.of());
		List<OccurrencePreview> previews = new ArrayList<>();
		for (int i = 0; i < occurrences.size(); i++) {
			Occurrence occurrence = occurrences.get(i);
			previews.add(new OccurrencePreview(occurrence.date(), occurrence.startsAt(), occurrence.endsAt(),
					occurrence.excluded(), issues.get(i)));
		}
		int conflictCount = (int) previews.stream()
			.filter((preview) -> !preview.excluded() && preview.hasBlockingIssue())
			.count();
		return new PlanPreviewResponse(previews,
				this.conflicts.warnings(details.serviceType().id(), details.assignments()), conflictCount);
	}

	/**
	 * Takes the locks of the assigned staff and resources, then rejects the plan if any of its
	 * occurrences has a blocking problem. The response lists those occurrences with their problems.
	 */
	private void lockAndRejectConflicts(List<Occurrence> occurrences, Assignments assignments, ZoneId zone,
			Collection<UUID> ignoredEventIds) {
		this.locks.lock(assignments.staffIds(), assignments.resourceIds());
		List<List<ScheduleIssue>> issues = this.conflicts.find(occurrences, assignments, zone, ignoredEventIds);
		List<OccurrencePreview> conflicting = new ArrayList<>();
		for (int i = 0; i < occurrences.size(); i++) {
			List<ScheduleIssue> blocking = issues.get(i).stream().filter(ScheduleIssue::blocking).toList();
			if (!blocking.isEmpty()) {
				Occurrence occurrence = occurrences.get(i);
				conflicting.add(new OccurrencePreview(occurrence.date(), occurrence.startsAt(), occurrence.endsAt(),
						false, blocking));
			}
		}
		if (conflicting.isEmpty()) {
			return;
		}
		boolean resourceTaken = conflicting.stream()
			.flatMap((preview) -> preview.issues().stream())
			.anyMatch((issue) -> issue.type() == IssueType.RESOURCE_BUSY);
		ErrorCode code = resourceTaken ? ErrorCode.RESOURCE_CAPACITY_EXCEEDED : ErrorCode.STAFF_SCHEDULE_CONFLICT;
		String detail = conflicting.size() + " of " + occurrences.size()
				+ " occurrences conflict with other events or staff leave, e.g. on " + conflicting.get(0).date() + ".";
		throw new ApiException(code, detail, Map.of("conflicts", conflicting));
	}

	private ScheduleEvent newEvent(Details details, @Nullable UUID seriesId, Occurrence occurrence,
			UUID actorAccountId) {
		ScheduleEvent event = new ScheduleEvent(details.kind(), details.serviceType().id(), seriesId, details.title(),
				details.capacity(), details.visibility(), occurrence.startsAt(), occurrence.endsAt(), actorAccountId);
		event.replaceAssignments(details.assignments().staff(), details.assignments().resources());
		return event;
	}

	/**
	 * Checks the details shared by one-off events and series and fills in the defaults.
	 */
	private Details details(EventDetailsRequest request) {
		if (!EventKind.PLANNABLE.contains(request.kind())) {
			throw ApiException.invalidField("kind", FieldErrorCode.INVALID_VALUE,
					"Only CLASS, PERSONAL_TRAINING and WORKSHOP events can be planned here.");
		}
		ServiceTypeSummary serviceType = this.serviceTypes.findById(request.serviceTypeId())
			.filter(ServiceTypeSummary::active)
			.orElseThrow(() -> new ApiException(ErrorCode.SERVICE_TYPE_UNAVAILABLE,
					"The service type does not exist or is not active."));
		boolean personal = request.kind() == EventKind.PERSONAL_TRAINING;
		String title = (request.title() == null || request.title().isBlank()) ? serviceType.name()
				: request.title().strip();
		if (title.length() > ScheduleDtos.MAX_TITLE_LENGTH) {
			title = title.substring(0, ScheduleDtos.MAX_TITLE_LENGTH);
		}
		EventVisibility visibility = (request.visibility() != null) ? request.visibility()
				: (personal ? EventVisibility.PRIVATE : EventVisibility.PUBLIC);

		Assignments assignments = assignments(serviceType.id(), request.capacity(), request.staff(),
				request.resources(), null);
		Integer capacity = request.capacity();
		if (capacity == null) {
			capacity = serviceType.maxParticipants();
			if (capacity == null && personal) {
				capacity = 1;
			}
			Integer roomLimit = roomLimit(assignments);
			if (capacity != null && roomLimit != null && capacity > roomLimit) {
				capacity = roomLimit;
			}
		}
		return new Details(request.kind(), serviceType, title, (capacity != null) ? capacity.shortValue() : null,
				visibility, assignments);
	}

	/**
	 * Checks that the staff members and resources exist and are active, that the service type may
	 * take place in each room and that the rooms hold {@code capacity} people. Staff and rooms the
	 * event already has may stay even if they were deactivated or the room's rules changed since.
	 *
	 * @param current the event whose assignments are replaced, or {@code null} for a new event
	 */
	private Assignments assignments(@Nullable UUID serviceTypeId, @Nullable Integer capacity,
			@Nullable List<StaffAssignmentRequest> staffRequests,
			@Nullable List<ResourceAssignmentRequest> resourceRequests, @Nullable ScheduleEvent current) {
		List<EventStaffAssignment> staff = (staffRequests != null)
				? staffRequests.stream().map(StaffAssignmentRequest::toAssignment).toList() : List.of();
		List<EventResourceAssignment> resourceAssignments = (resourceRequests != null)
				? resourceRequests.stream().map(ResourceAssignmentRequest::toAssignment).toList() : List.of();
		requireDistinct(staff.stream().map(EventStaffAssignment::staffId).toList(), "staff",
				"Each staff member may be assigned only once.");
		requireDistinct(resourceAssignments.stream().map(EventResourceAssignment::resourceId).toList(), "resources",
				"Each room may be assigned only once.");

		Set<UUID> keptStaff = (current != null) ? current.getStaff()
			.stream()
			.map(EventStaffAssignment::staffId)
			.collect(Collectors.toSet()) : Set.of();
		Set<UUID> keptResources = (current != null) ? current.getResources()
			.stream()
			.map(EventResourceAssignment::resourceId)
			.collect(Collectors.toSet()) : Set.of();

		Set<UUID> staffIds = staff.stream().map(EventStaffAssignment::staffId).collect(Collectors.toSet());
		long usableStaff = this.staffProfiles.findAllById(staffIds)
			.stream()
			.filter((member) -> member.active() || keptStaff.contains(member.id()))
			.count();
		if (usableStaff != staffIds.size()) {
			throw new ApiException(ErrorCode.STAFF_UNAVAILABLE, "A staff member does not exist or is not active.");
		}

		Map<UUID, Resource> resourcesById = this.resources
			.findAllByIdIn(resourceAssignments.stream().map(EventResourceAssignment::resourceId).toList())
			.stream()
			.filter((resource) -> resource.isActive() || keptResources.contains(resource.getId()))
			.collect(Collectors.toMap(Resource::getId, Function.identity()));
		if (resourcesById.size() != resourceAssignments.size()) {
			throw new ApiException(ErrorCode.RESOURCE_UNAVAILABLE,
					"A room does not exist or is not active.");
		}
		Assignments assignments = new Assignments(staff, resourceAssignments, resourcesById);

		List<Resource> rooms = rooms(assignments);
		if (serviceTypeId != null && !rooms.isEmpty()) {
			Set<UUID> allowedRooms = this.roomRules.findAllByKeyServiceTypeId(serviceTypeId)
				.stream()
				.map(ServiceTypeResource::getResourceId)
				.collect(Collectors.toSet());
			rooms.stream()
				.filter((room) -> !allowedRooms.contains(room.getId()) && !keptResources.contains(room.getId()))
				.findFirst()
				.ifPresent((room) -> {
					throw new ApiException(ErrorCode.SERVICE_NOT_ALLOWED_IN_ROOM,
							"The service type may not take place in the room " + room.getName() + ".");
				});
		}
		Integer roomLimit = roomLimit(assignments);
		if (capacity != null && roomLimit != null && capacity > roomLimit) {
			throw ApiException.invalidField("capacity", FieldErrorCode.OUT_OF_RANGE,
					"The rooms hold at most " + roomLimit + " people.");
		}
		return assignments;
	}

	private Occurrence occurrence(CreateEventRequest request, ZoneId zone) {
		Instant startsAt = request.startsAt().truncatedTo(ChronoUnit.SECONDS);
		Instant endsAt = request.endsAt().truncatedTo(ChronoUnit.SECONDS);
		if (!endsAt.isAfter(startsAt)) {
			throw ApiException.invalidField("endsAt", FieldErrorCode.OUT_OF_RANGE, "The event must end after it starts.");
		}
		if (Duration.between(startsAt, endsAt).compareTo(MAX_EVENT_DURATION) > 0) {
			throw ApiException.invalidField("endsAt", FieldErrorCode.OUT_OF_RANGE,
					"An event may last at most " + MAX_EVENT_DURATION.toHours() + " hours.");
		}
		if (!startsAt.isAfter(this.clock.instant())) {
			throw ApiException.invalidField("startsAt", FieldErrorCode.OUT_OF_RANGE, "The event must start in the future.");
		}
		return new Occurrence(LocalDate.ofInstant(startsAt, zone), startsAt, endsAt, false);
	}

	/**
	 * The occurrences of a recurring plan, including the excluded dates. Weeks are counted from
	 * the week of {@code startsOn}, so with an interval of two weeks every weekday falls in the
	 * same weeks. Occurrences that would already have started are left out.
	 */
	private List<Occurrence> occurrences(CreateSeriesRequest request, ZoneId zone) {
		Instant now = this.clock.instant();
		LocalDate today = LocalDate.ofInstant(now, zone);
		if (request.startsOn().isBefore(today)) {
			throw ApiException.invalidField("startsOn", FieldErrorCode.OUT_OF_RANGE,
					"The series must not start in the past.");
		}
		if (request.endsOn().isBefore(request.startsOn())) {
			throw ApiException.invalidField("endsOn", FieldErrorCode.OUT_OF_RANGE,
					"The series must not end before it starts.");
		}
		if (request.endsOn().isAfter(request.startsOn().plusYears(1))) {
			throw ApiException.invalidField("endsOn", FieldErrorCode.OUT_OF_RANGE,
					"A series may last at most one year.");
		}
		Set<LocalDate> excluded = (request.excludedDates() != null) ? request.excludedDates() : Set.of();
		List<Integer> weekdays = request.weekdays().stream().sorted().toList();
		Duration duration = Duration.ofMinutes(request.durationMinutes());
		List<Occurrence> result = new ArrayList<>();
		LocalDate week = request.startsOn().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		for (; !week.isAfter(request.endsOn()); week = week.plusWeeks(intervalWeeks(request))) {
			for (int weekday : weekdays) {
				LocalDate date = week.plusDays(weekday - 1L);
				if (date.isBefore(request.startsOn()) || date.isAfter(request.endsOn())) {
					continue;
				}
				Instant startsAt = date.atTime(request.localStartTime()).atZone(zone).toInstant();
				if (startsAt.isAfter(now)) {
					result.add(new Occurrence(date, startsAt, startsAt.plus(duration), excluded.contains(date)));
				}
			}
		}
		if (result.stream().allMatch(Occurrence::excluded)) {
			throw ApiException.invalidField("weekdays", FieldErrorCode.INVALID_VALUE,
					"No occurrence falls between startsOn and endsOn on the chosen weekdays.");
		}
		List<Occurrence> sorted = result.stream()
			.filter((o) -> !o.excluded())
			.sorted(Comparator.comparing(Occurrence::startsAt))
			.toList();
		for (int i = 1; i < sorted.size(); i++) {
			if (sorted.get(i - 1).overlaps(sorted.get(i).startsAt(), sorted.get(i).endsAt())) {
				throw ApiException.invalidField("durationMinutes", FieldErrorCode.OUT_OF_RANGE,
						"Occurrences on neighbouring weekdays would overlap.");
			}
		}
		return result;
	}

	private static int intervalWeeks(CreateSeriesRequest request) {
		return (request.intervalWeeks() != null) ? request.intervalWeeks() : 1;
	}

	private static List<Resource> rooms(Assignments assignments) {
		return assignments.resourcesById()
			.values()
			.stream()
			.filter((resource) -> resource.getKind() == ResourceKind.ROOM)
			.sorted(Comparator.comparing(Resource::getName))
			.toList();
	}

	/**
	 * How many people the assigned rooms hold together, or {@code null} when there is no room or a
	 * room without a limit.
	 */
	private static @Nullable Integer roomLimit(Assignments assignments) {
		List<Resource> rooms = rooms(assignments);
		if (rooms.isEmpty() || rooms.stream().map(Resource::getMaxPeople).anyMatch(Objects::isNull)) {
			return null;
		}
		return rooms.stream().mapToInt(Resource::getMaxPeople).sum();
	}

	private static void requireDistinct(List<UUID> ids, String field, String message) {
		if (new HashSet<>(ids).size() != ids.size()) {
			throw ApiException.invalidField(field, FieldErrorCode.INVALID_VALUE, message);
		}
	}

	/** Checked details of a planned event, with defaults filled in. */
	private record Details(EventKind kind, ServiceTypeSummary serviceType, String title, @Nullable Short capacity,
			EventVisibility visibility, Assignments assignments) {
	}

}
