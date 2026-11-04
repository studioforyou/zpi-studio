package studioforyou.pl.backend.scheduling.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.OwnSeriesRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.PlanPreviewResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.SeriesPlanResponse;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recurring group classes the signed-in trainer adds to their own schedule. The staff member
 * comes from the account, never from the request.
 */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/recurrence-series")
@Tag(name = "scheduling")
class OwnRecurrenceSeriesController {

	private final SchedulePlanner planner;

	private final StaffProfiles staffProfiles;

	OwnRecurrenceSeriesController(SchedulePlanner planner, StaffProfiles staffProfiles) {
		this.planner = planner;
		this.staffProfiles = staffProfiles;
	}

	@PostMapping
	@Operation(summary = "Add a recurring group class to my schedule", operationId = "createOwnRecurrenceSeries",
			description = "Creates one series for each weekday and all their occurrences between startsOn and endsOn, "
					+ "except excludedDates, run by the signed-in trainer. Any occurrence that conflicts with "
					+ "another event or the trainer's leave rejects the whole plan; the response then lists the "
					+ "conflicting occurrences in `conflicts`.")
	@ApiResponse(responseCode = "201", description = "The created series.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the series starts in the past, "
			+ "lasts over a year or has no occurrences.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "RESOURCE_CAPACITY_EXCEEDED or STAFF_SCHEDULE_CONFLICT, with "
			+ "`conflicts`.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "SERVICE_TYPE_UNAVAILABLE, STAFF_UNAVAILABLE, "
			+ "RESOURCE_UNAVAILABLE or SERVICE_NOT_ALLOWED_IN_ROOM.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<SeriesPlanResponse> createOwnRecurrenceSeries(@Valid @RequestBody OwnSeriesRequest request,
			Authentication authentication) {
		SeriesPlanResponse created = this.planner.createSeries(request.runBy(staffId(authentication)),
				CurrentAccount.of(authentication).id());
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@PostMapping("/preview")
	@Operation(summary = "Check a recurring group class before adding it", operationId = "previewOwnRecurrenceSeries",
			description = "Validates the request like adding the series and lists every occurrence, including the "
					+ "excluded ones, with its conflicts and warnings, without saving anything.")
	@ApiResponse(responseCode = "200", description = "The occurrences and their problems.")
	@ApiResponse(responseCode = "404", description = "The account has no staff profile.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	PlanPreviewResponse previewOwnRecurrenceSeries(@Valid @RequestBody OwnSeriesRequest request,
			Authentication authentication) {
		return this.planner.previewSeries(request.runBy(staffId(authentication)));
	}

	private UUID staffId(Authentication authentication) {
		return this.staffProfiles.getByAccount(CurrentAccount.of(authentication).id()).id();
	}

}
