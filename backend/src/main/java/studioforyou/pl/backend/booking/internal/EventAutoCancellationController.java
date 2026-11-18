package studioforyou.pl.backend.booking.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.AutoCancellationDtos.AutoCancellationResponse;
import studioforyou.pl.backend.booking.internal.AutoCancellationDtos.ChangeAutoCancellationRequest;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Administrators see whether a group class will be cancelled for too few participants and force it
 * to run anyway (#136).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/schedule-events/{eventId}/auto-cancellation")
@Tag(name = "booking")
class EventAutoCancellationController {

	private static final String PROBLEM = "application/problem+json";

	private final EventAutoCancellationService service;

	EventAutoCancellationController(EventAutoCancellationService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Get the automatic cancellation of a group class", operationId = "getEventAutoCancellation",
			description = "Whether the rule cancelling classes with too few participants applies to the class, how "
					+ "many participants it has and needs, when they are counted, and whether an administrator forced "
					+ "the class to run or switched the rule off for its fixed group.")
	@ApiResponse(responseCode = "200", description = "The class's automatic cancellation.")
	@ApiResponse(responseCode = "404", description = "No such group class.", content = @Content(mediaType = PROBLEM,
			schema = @Schema(implementation = ApiProblem.class)))
	AutoCancellationResponse get(@PathVariable UUID eventId) {
		return this.service.status(eventId);
	}

	@PutMapping
	@Operation(summary = "Force a group class to run", operationId = "changeEventAutoCancellation",
			description = "forceRun true keeps the class from being cancelled automatically, however few participants "
					+ "it has; false lets the rule decide again. It can be changed until the class starts; a class "
					+ "already cancelled automatically stays cancelled. The class's version does not change, and the "
					+ "change is kept in the audit log. Repeating it changes nothing.")
	@ApiResponse(responseCode = "200", description = "The class's automatic cancellation after the change.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when forceRun is missing.", content = @Content(
			mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such group class.", content = @Content(mediaType = PROBLEM,
			schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "EVENT_NOT_EDITABLE when the class is cancelled, completed or "
			+ "has started.", content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	AutoCancellationResponse change(@PathVariable UUID eventId, @Valid @RequestBody ChangeAutoCancellationRequest request,
			AccountAuthentication authentication) {
		UUID actor = authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
		return this.service.changeForceRun(actor, eventId, request.forceRun());
	}

}
