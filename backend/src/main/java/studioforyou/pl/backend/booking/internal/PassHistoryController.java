package studioforyou.pl.backend.booking.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.PassHistoryDtos.PassHistoryCategory;
import studioforyou.pl.backend.booking.internal.PassHistoryDtos.PassHistoryResponse;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Administrators read everything that changed a participant's pass, to explain how it was used
 * (#85). The pass belongs to membership; booking adds the classes and sessions its entries were for.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "membership")
class PassHistoryController {

	private final PassHistoryQueries queries;

	PassHistoryController(PassHistoryQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/api/v1/customers/{customerId}/passes/{passId}/history")
	@Operation(summary = "Read a participant's pass history", operationId = "getCustomerPassHistory",
			description = "Every change of the pass, newest first: its activation, each entry a booking took, "
					+ "settled as used or gave back (with the class, who booked and cancelled it, how and the "
					+ "attendance), corrections, refunds after upheld disputes, extensions, suspensions with their "
					+ "days and their cancellations, make-up credits granted from it and the participant's decision "
					+ "not to renew it. Each change has its author, note and, for a change of the balance, the "
					+ "balance after it. Upcoming bookings are included, since they already hold an entry. "
					+ "Read-only.")
	@ApiResponse(responseCode = "200", description = "One page of the history and the number of changes per category.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: an unknown category or invalid paging.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such pass of this participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	PassHistoryResponse history(@PathVariable UUID customerId, @PathVariable UUID passId,
			@Parameter(description = "Only changes of this category: ENTRIES, CORRECTIONS, VALIDITY, MAKEUP or PASS.")
			@RequestParam(required = false) @Nullable PassHistoryCategory category,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return queries.history(customerId, passId, category, page, size);
	}

}
