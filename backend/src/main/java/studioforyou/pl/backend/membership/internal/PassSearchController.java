package studioforyou.pl.backend.membership.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Passes of all participants matching a search, for administrators.
 */
@RestController
@RequestMapping("/api/v1/customer-passes")
@Tag(name = "membership")
@PreAuthorize("hasRole('ADMIN')")
public class PassSearchController {

	private final CustomerPassService passes;

	private final StudioSettings studio;

	public PassSearchController(CustomerPassService passes, StudioSettings studio) {
		this.passes = passes;
		this.studio = studio;
	}

	@GetMapping
	@ApiResponse(responseCode = "200", description = "The matching passes.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: the query is blank.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@Operation(summary = "Search participants' passes", operationId = "searchCustomerPasses",
			description = "Passes of the active participants whose name, phone number or e-mail address contains the "
					+ "query (a child also matches its guardian's address), by participant in name order and newest "
					+ "first. Each pass comes with its holder's name and e-mail address, so participants with the "
					+ "same name can be told apart. At most " + CustomerPassService.MAX_SEARCHED_PARTICIPANTS
					+ " participants are searched.")
	public PageResponse<HeldPassResponse> search(
			@Parameter(description = "Part of the first and/or last name, the phone number or the e-mail address.")
			@RequestParam String query,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		if (query.isBlank()) {
			throw ApiException.invalidField("query", FieldErrorCode.REQUIRED, "Type part of a name, phone or e-mail.");
		}
		PageResponse<CustomerPassService.HeldPass> found = passes.search(query, page, size);
		Map<UUID, List<PassSuspension>> suspensions = passes
			.suspensionsOf(found.items().stream().map(CustomerPassService.HeldPass::pass).toList());
		String currency = studio.currency();
		return found.map((held) -> new HeldPassResponse(PassHolderResponse.from(held.holder()),
				CustomerPassResponse.from(held.pass(), currency,
						suspensions.getOrDefault(held.pass().getId(), List.of()), studio.today())));
	}

}
