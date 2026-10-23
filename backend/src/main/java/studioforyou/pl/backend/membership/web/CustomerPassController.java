package studioforyou.pl.backend.membership.web;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.membership.internal.CustomerPass;
import studioforyou.pl.backend.membership.internal.CustomerPassService;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Passes held by a participant. For now only administrators read and assign them.
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}/passes")
@Tag(name = "membership")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerPassController {

	private final CustomerPassService passes;

	private final StudioSettings studio;

	public CustomerPassController(CustomerPassService passes, StudioSettings studio) {
		this.passes = passes;
		this.studio = studio;
	}

	@GetMapping
	@Operation(summary = "List a participant's passes", operationId = "listCustomerPasses",
			description = "All passes of the participant, newest first.")
	@ApiResponse(responseCode = "200", description = "The participant's passes.")
	@ApiResponse(responseCode = "404", description = "No such participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CustomerPassListResponse list(@PathVariable UUID customerId) {
		String currency = studio.currency();
		return new CustomerPassListResponse(
				passes.list(customerId).stream().map((pass) -> CustomerPassResponse.from(pass, currency)).toList());
	}

	@GetMapping("/{passId}")
	@Operation(summary = "Get a participant's pass", operationId = "getCustomerPass")
	@ApiResponse(responseCode = "200", description = "The pass.")
	@ApiResponse(responseCode = "404", description = "No such pass of this participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CustomerPassResponse get(@PathVariable UUID customerId, @PathVariable UUID passId) {
		return CustomerPassResponse.from(passes.get(customerId, passId), studio.currency());
	}

	@PostMapping
	@Operation(summary = "Assign a pass to a participant", operationId = "assignCustomerPass",
			description = "Creates an active pass, or a scheduled one when it starts after today, with the variant's "
					+ "entries and validity. The pass is not linked to an order; the payment is handled separately.")
	@ApiResponse(responseCode = "201", description = "The assigned pass.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the pass would already be expired "
			+ "or the price is in another currency than the variant's.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such participant or variant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CUSTOMER_NOT_ACTIVE, PASS_PRODUCT_ARCHIVED, PASS_VARIANT_INACTIVE "
			+ "or PASS_AUDIENCE_MISMATCH (e.g. a children's pass for an adult).", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<CustomerPassResponse> assign(@PathVariable UUID customerId,
			@Valid @RequestBody AssignPassRequest request, Authentication authentication) {
		CustomerPass pass = passes.assign(customerId, request.toCommand(), CurrentAccount.of(authentication).id());
		return ResponseEntity.created(URI.create("/api/v1/customers/" + customerId + "/passes/" + pass.getId()))
			.body(CustomerPassResponse.from(pass, studio.currency()));
	}

}
