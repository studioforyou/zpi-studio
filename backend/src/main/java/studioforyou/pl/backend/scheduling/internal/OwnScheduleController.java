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
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleQueries.TimetableFilter;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in trainer's own schedule: the classes and trainings they run or assist. The staff
 * member comes from the account, never from the request.
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

	OwnScheduleController(ScheduleQueries queries, StaffProfiles staffProfiles) {
		this.queries = queries;
		this.staffProfiles = staffProfiles;
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
		UUID staffId = this.staffProfiles.getByAccount(CurrentAccount.of(authentication).id()).id();
		return new ListResponse<>(this.queries.findEvents(new TimetableFilter(from, to, TRAINER_KINDS, null, null,
				null, List.of(staffId), null, null, null)));
	}

}
