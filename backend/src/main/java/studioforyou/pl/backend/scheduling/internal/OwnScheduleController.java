package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.OwnEventRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.PlanPreviewResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleQueries.TimetableFilter;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in trainer's own schedule: the classes and trainings they run or assist, and the
 * one-off group classes they add to it. The staff member comes from the account, never from the
 * request.
 */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/schedule-events")
@Tag(name = "scheduling")
class OwnScheduleController {

	/** Blocks of rooms and staff are planning details, not part of a trainer's day. */
	private static final List<EventKind> TRAINER_KINDS = List
		.copyOf(EnumSet.complementOf(EnumSet.of(EventKind.BLOCK)));

	private final ScheduleQueries queries;

	private final StaffProfiles staffProfiles;

	private final SchedulePlanner planner;

	OwnScheduleController(ScheduleQueries queries, StaffProfiles staffProfiles, SchedulePlanner planner) {
		this.queries = queries;
		this.staffProfiles = staffProfiles;
		this.planner = planner;
	}

	@GetMapping
	@Operation(summary = "List my events in a time window", operationId = "listOwnScheduleEvents",
			description = "Events the signed-in trainer runs or assists that overlap [from, to), earliest first. "
					+ "Includes completed and cancelled events; blocks are left out.")
	@ApiResponse(responseCode = "200", description = "The trainer's events.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when to is not after from or the window "
			+ "is longer than 93 days.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ListResponse<ScheduleEventResponse> listOwnScheduleEvents(
			@Parameter(description = "Start of the window, inclusive.", example = "2026-11-02T00:00:00Z")
			@RequestParam Instant from,
			@Parameter(description = "End of the window, exclusive.", example = "2026-11-09T00:00:00Z")
			@RequestParam Instant to, Authentication authentication) {
		ScheduleEventController.requireValidWindow(from, to);
		UUID staffId = staffId(authentication);
		return new ListResponse<>(this.queries.findEvents(new TimetableFilter(from, to, TRAINER_KINDS, null, null,
				null, List.of(staffId), null, null, null)));
	}

	@PostMapping
	@Operation(summary = "Add a group class to my schedule", operationId = "createOwnScheduleEvent",
			description = "Creates a one-off public group class run by the signed-in trainer, with the given rooms. "
					+ "A room or the trainer taken at that time rejects the class; the response then lists the "
					+ "conflicts in `conflicts`.")
	@ApiResponse(responseCode = "201", description = "The created class.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the class is not in the future "
			+ "or the capacity exceeds the rooms' limit.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "RESOURCE_CAPACITY_EXCEEDED or STAFF_SCHEDULE_CONFLICT, with "
			+ "`conflicts`: the occurrence and its blocking problems.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "SERVICE_TYPE_UNAVAILABLE, STAFF_UNAVAILABLE, "
			+ "RESOURCE_UNAVAILABLE or SERVICE_NOT_ALLOWED_IN_ROOM.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ScheduleEventResponse> createOwnScheduleEvent(@Valid @RequestBody OwnEventRequest request,
			Authentication authentication) {
		ScheduleEventResponse created = this.planner.createEvent(request.runBy(staffId(authentication)),
				CurrentAccount.of(authentication).id());
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@PostMapping("/preview")
	@Operation(summary = "Check a group class before adding it", operationId = "previewOwnScheduleEvent",
			description = "Validates the request like adding the class and lists its conflicts and warnings, "
					+ "e.g. a room taken by another event or a time outside the trainer's working hours, without "
					+ "saving anything.")
	@ApiResponse(responseCode = "200", description = "The class's occurrence and its problems.")
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	PlanPreviewResponse previewOwnScheduleEvent(@Valid @RequestBody OwnEventRequest request,
			Authentication authentication) {
		return this.planner.previewEvent(request.runBy(staffId(authentication)));
	}

	private UUID staffId(Authentication authentication) {
		return this.staffProfiles.getByAccount(CurrentAccount.of(authentication).id()).id();
	}

}
