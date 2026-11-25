package studioforyou.pl.backend.booking.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.DisputeResponse;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.DisputeStatus;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDisputeDtos.ResolveRequest;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Administrators review the discrepancies clients report about held personal sessions and decide on
 * them, so the session is settled correctly (#113).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/personal-training-disputes")
@Tag(name = "booking")
@ApiResponse(responseCode = "404", description = "The dispute does not exist.", content = @Content(
		mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class PersonalTrainingDisputeController {

	private final PersonalTrainingDisputes disputes;

	PersonalTrainingDisputeController(PersonalTrainingDisputes disputes) {
		this.disputes = disputes;
	}

	@GetMapping
	@Operation(summary = "List personal-training disputes", operationId = "listPersonalTrainingDisputes",
			description = "Open disputes oldest first; resolved ones with the latest decision first.")
	@ApiResponse(responseCode = "200", description = "One page of the disputes with the given status.")
	PageResponse<DisputeResponse> list(
			@Parameter(description = "OPEN (waiting for a decision) or RESOLVED.")
			@RequestParam(defaultValue = "OPEN") DisputeStatus status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return this.disputes.list(status, page, size);
	}

	@GetMapping("/{disputeId}")
	@Operation(summary = "Get a personal-training dispute", operationId = "getPersonalTrainingDispute",
			description = "An open dispute also comes with how the session was paid for and what each decision "
					+ "would do to it.")
	@ApiResponse(responseCode = "200", description = "The dispute.")
	DisputeResponse get(@PathVariable UUID disputeId) {
		return this.disputes.get(disputeId);
	}

	@PostMapping("/{disputeId}/resolve")
	@Operation(summary = "Decide on a personal-training dispute", operationId = "resolvePersonalTrainingDispute",
			description = "UPHELD gives the session's pass entry back as a correction, or a make-up credit that paid "
					+ "for it with the same expiry; an entry also goes back to an expired or cancelled pass, which then "
					+ "has to be extended to be used. UPHELD_MAKEUP grants a new make-up credit valid for the given "
					+ "days and keeps the entry used. REJECTED keeps the entry used. The note is required and shown to "
					+ "the client. The session's settlement becomes FINAL and the decision is kept in the audit log.")
	@ApiResponse(responseCode = "200", description = "The resolved dispute.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, e.g. a blank note, or UPHELD_MAKEUP "
			+ "without makeupValidityDays.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "DISPUTE_ALREADY_RESOLVED, or VERSION_CONFLICT when the "
			+ "session changed since it was loaded.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "DISPUTE_RESOLUTION_UNAVAILABLE with the notice, e.g. "
			+ "UPHELD_MAKEUP for an unlimited pass or UPHELD for an expired make-up credit.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	DisputeResponse resolve(@PathVariable UUID disputeId, @Valid @RequestBody ResolveRequest request,
			AccountAuthentication authentication) {
		UUID actorId = authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
		return this.disputes.resolve(actorId, disputeId, request);
	}

}
