package studioforyou.pl.backend.scheduling.internal;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ChangeAssignmentsRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.CreateEventRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.PlanPreviewResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleQueries.TimetableFilter;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.shared.api.ListResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The studio's schedule for administrators: every kind of event in a time window, one-off events
 * and the staff and rooms assigned to events.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/schedule-events")
@Tag(name = "scheduling")
class ScheduleEventController {

	/** The longest time window of one timetable query, enough for a month view with its edges. */
	static final Duration MAX_WINDOW = Duration.ofDays(93);

	private final ScheduleQueries queries;

	private final SchedulePlanner planner;

	ScheduleEventController(ScheduleQueries queries, SchedulePlanner planner) {
		this.queries = queries;
		this.planner = planner;
	}

	@GetMapping
	@Operation(summary = "List events in a time window", operationId = "listScheduleEvents",
			description = "Events of every kind and status that overlap [from, to), earliest first. Each filter "
					+ "parameter can be repeated; an event matches when it has one of the given values.")
	@ApiResponse(responseCode = "200", description = "The events.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when to is not after from or the window "
			+ "is longer than 93 days.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ListResponse<ScheduleEventResponse> listScheduleEvents(
			@Parameter(description = "Start of the window, inclusive.", example = "2026-11-02T00:00:00Z")
			@RequestParam Instant from,
			@Parameter(description = "End of the window, exclusive.", example = "2026-11-09T00:00:00Z")
			@RequestParam Instant to,
			@RequestParam(required = false) @Nullable List<EventKind> kind,
			@RequestParam(required = false) @Nullable List<EventStatus> status,
			@RequestParam(required = false) @Nullable List<EventVisibility> visibility,
			@RequestParam(required = false) @Nullable List<UUID> serviceTypeId,
			@Parameter(description = "Events run or assisted by these staff members.")
			@RequestParam(required = false) @Nullable List<UUID> staffId,
			@Parameter(description = "Events that use these rooms.")
			@RequestParam(required = false) @Nullable List<UUID> resourceId,
			@RequestParam(required = false) @Nullable List<UUID> trainingGroupId,
			@RequestParam(required = false) @Nullable List<UUID> seriesId) {
		requireValidWindow(from, to);
		return new ListResponse<>(this.queries.findEvents(new TimetableFilter(from, to, kind, status, visibility,
				serviceTypeId, staffId, resourceId, trainingGroupId, seriesId)));
	}

	/** Rejects a timetable window that is empty or longer than {@link #MAX_WINDOW}. */
	static void requireValidWindow(Instant from, Instant to) {
		if (!to.isAfter(from)) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE, "to must be after from.");
		}
		if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE,
					"The window may be at most " + MAX_WINDOW.toDays() + " days long.");
		}
	}

	@GetMapping("/{eventId}")
	@Operation(summary = "Get an event", operationId = "getScheduleEvent")
	@ApiResponse(responseCode = "200", description = "The event.")
	@ApiResponse(responseCode = "404", description = "The event does not exist.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ScheduleEventResponse getScheduleEvent(@PathVariable UUID eventId) {
		return this.queries.findEvent(eventId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
	}

	@PostMapping
	@Operation(summary = "Create a one-off event", operationId = "createScheduleEvent",
			description = "Creates a scheduled event with its staff and rooms. A room or a staff member "
					+ "taken at that time rejects the event; the response then lists the conflicts in `conflicts`.")
	@ApiResponse(responseCode = "201", description = "The created event.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the event is not in the future "
			+ "or the capacity exceeds the rooms' limit.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "RESOURCE_CAPACITY_EXCEEDED or STAFF_SCHEDULE_CONFLICT, with "
			+ "`conflicts`: the occurrences and their blocking problems.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "SERVICE_TYPE_UNAVAILABLE, STAFF_UNAVAILABLE, "
			+ "RESOURCE_UNAVAILABLE or SERVICE_NOT_ALLOWED_IN_ROOM.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ScheduleEventResponse> createScheduleEvent(@Valid @RequestBody CreateEventRequest request,
			Authentication authentication) {
		ScheduleEventResponse created = this.planner.createEvent(request, CurrentAccount.of(authentication).id());
		return ResponseEntity.created(URI.create("/api/v1/schedule-events/" + created.id())).body(created);
	}

	@PostMapping("/preview")
	@Operation(summary = "Check a one-off event before creating it", operationId = "previewScheduleEvent",
			description = "Validates the request like creating the event and lists its conflicts and warnings "
					+ "without saving anything.")
	@ApiResponse(responseCode = "200", description = "The event's occurrence and its problems.")
	PlanPreviewResponse previewScheduleEvent(@Valid @RequestBody CreateEventRequest request) {
		return this.planner.previewEvent(request);
	}

	@PutMapping("/{eventId}/assignments")
	@Operation(summary = "Replace the staff and rooms of an event", operationId = "changeScheduleEventAssignments",
			description = "With scope THIS_AND_FOLLOWING the change also applies to the later scheduled occurrences "
					+ "of the event's series and becomes the series' default. A change of one occurrence of a series "
					+ "marks it as an exception.")
	@ApiResponse(responseCode = "200", description = "The updated event.")
	@ApiResponse(responseCode = "404", description = "The event does not exist.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT, RESOURCE_CAPACITY_EXCEEDED or "
			+ "STAFF_SCHEDULE_CONFLICT.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "EVENT_NOT_EDITABLE, STAFF_UNAVAILABLE, RESOURCE_UNAVAILABLE "
			+ "or SERVICE_NOT_ALLOWED_IN_ROOM.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ScheduleEventResponse changeScheduleEventAssignments(@PathVariable UUID eventId,
			@Valid @RequestBody ChangeAssignmentsRequest request) {
		return this.planner.changeAssignments(eventId, request);
	}

}
