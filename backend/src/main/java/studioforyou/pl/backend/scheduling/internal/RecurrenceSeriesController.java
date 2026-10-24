package studioforyou.pl.backend.scheduling.internal;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.CreateSeriesRequest;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.PlanPreviewResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.SeriesPlanResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.SeriesResponse;
import studioforyou.pl.backend.shared.api.ApiProblem;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recurring events: a weekly rule in the studio's local time and the occurrences it generates
 * between its first and last day.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/recurrence-series")
@Tag(name = "scheduling")
class RecurrenceSeriesController {

	private final SchedulePlanner planner;

	RecurrenceSeriesController(SchedulePlanner planner) {
		this.planner = planner;
	}

	@PostMapping
	@Operation(summary = "Create a recurring event", operationId = "createRecurrenceSeries",
			description = "Creates one series for each weekday and all their occurrences between startsOn and endsOn, "
					+ "except excludedDates, with the given staff and rooms. Any occurrence that conflicts "
					+ "with another event or staff leave rejects the whole plan; the response then lists the "
					+ "conflicting occurrences in `conflicts`. Location points to the first series.")
	@ApiResponse(responseCode = "201", description = "The created series.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the series starts in the past, "
			+ "lasts over a year or has no occurrences.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "RESOURCE_CAPACITY_EXCEEDED or STAFF_SCHEDULE_CONFLICT, with "
			+ "`conflicts`.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "SERVICE_TYPE_UNAVAILABLE, STAFF_UNAVAILABLE, "
			+ "RESOURCE_UNAVAILABLE or SERVICE_NOT_ALLOWED_IN_ROOM.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<SeriesPlanResponse> createRecurrenceSeries(@Valid @RequestBody CreateSeriesRequest request,
			Authentication authentication) {
		SeriesPlanResponse created = this.planner.createSeries(request, CurrentAccount.of(authentication).id());
		return ResponseEntity.created(URI.create("/api/v1/recurrence-series/" + created.items().get(0).id()))
			.body(created);
	}

	@PostMapping("/preview")
	@Operation(summary = "Check a recurring event before creating it", operationId = "previewRecurrenceSeries",
			description = "Validates the request like creating the series and lists every occurrence, including the "
					+ "excluded ones, with its conflicts and warnings, without saving anything.")
	@ApiResponse(responseCode = "200", description = "The occurrences and their problems.")
	PlanPreviewResponse previewRecurrenceSeries(@Valid @RequestBody CreateSeriesRequest request) {
		return this.planner.previewSeries(request);
	}

	@GetMapping("/{seriesId}")
	@Operation(summary = "Get a recurring series", operationId = "getRecurrenceSeries")
	@ApiResponse(responseCode = "200", description = "The series.")
	@ApiResponse(responseCode = "404", description = "The series does not exist.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	SeriesResponse getRecurrenceSeries(@PathVariable UUID seriesId) {
		return this.planner.getSeries(seriesId);
	}

}
