package studioforyou.pl.backend.membership.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
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
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Passes held by a participant. For now only administrators read, assign, extend and suspend them.
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
		List<CustomerPass> held = passes.list(customerId);
		Map<UUID, List<PassSuspension>> suspensions = passes.suspensionsOf(held);
		return new CustomerPassListResponse(held.stream()
			.map((pass) -> response(pass, suspensions.getOrDefault(pass.getId(), List.of())))
			.toList());
	}

	@GetMapping("/{passId}")
	@Operation(summary = "Get a participant's pass", operationId = "getCustomerPass")
	@ApiResponse(responseCode = "200", description = "The pass.")
	@ApiResponse(responseCode = "404", description = "No such pass of this participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CustomerPassResponse get(@PathVariable UUID customerId, @PathVariable UUID passId) {
		return response(passes.get(customerId, passId));
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
			.body(response(pass, List.of()));
	}

	@PostMapping("/{passId}/extend")
	@Operation(summary = "Extend a participant's pass", operationId = "extendCustomerPass",
			description = "Moves the pass's last day to a later date, as an exception agreed with the participant. "
					+ "Allowed for any product. An expired pass becomes usable again. The change is kept in the "
					+ "pass history.")
	@ApiResponse(responseCode = "200", description = "The extended pass.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the new last day is not later "
			+ "than the current one or is in the past.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such pass of this participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT: the pass has changed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "PASS_NOT_USABLE: the pass waits for its payment or is cancelled.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public CustomerPassResponse extend(@PathVariable UUID customerId, @PathVariable UUID passId,
			@Valid @RequestBody ExtendPassRequest request, Authentication authentication) {
		passes.extend(customerId, passId, request.toCommand(), CurrentAccount.of(authentication).id());
		return response(passes.get(customerId, passId));
	}

	@PostMapping("/{passId}/suspend")
	@Operation(summary = "Suspend a participant's pass", operationId = "suspendCustomerPass",
			description = "Suspends the pass for a period from today on, within its validity, if its product allows "
					+ "suspension. The pass cannot be used to book classes in the period, and its last day moves by "
					+ "the number of suspended days. Reservations already made are not changed.")
	@ApiResponse(responseCode = "200", description = "The suspended pass with its suspensions.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the period starts in the past, "
			+ "ends before it starts or lies outside the pass's validity.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such pass of this participant.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT: the pass has changed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "PASS_SUSPENSION_NOT_ALLOWED: the product does not allow "
			+ "suspension, the period overlaps another suspension or the product's day limit would be exceeded; "
			+ "PASS_NOT_USABLE: the pass is not active or scheduled.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CustomerPassResponse suspend(@PathVariable UUID customerId, @PathVariable UUID passId,
			@Valid @RequestBody SuspendPassRequest request, Authentication authentication) {
		passes.suspend(customerId, passId, request.toCommand(), CurrentAccount.of(authentication).id());
		return response(passes.get(customerId, passId));
	}

	@PostMapping("/{passId}/suspensions/{suspensionId}/cancel")
	@Operation(summary = "Cancel a pass suspension", operationId = "cancelCustomerPassSuspension",
			description = "Cancels the suspended days from today on: a suspension that has not started is cancelled "
					+ "as a whole, one in progress ends yesterday. The pass's last day moves back by the days "
					+ "taken off.")
	@ApiResponse(responseCode = "200", description = "The pass with its remaining suspensions.")
	@ApiResponse(responseCode = "404", description = "No such pass or suspension.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT: the pass has changed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "PASS_SUSPENSION_NOT_ALLOWED: the suspension has already "
			+ "ended; PASS_NOT_USABLE: the pass is not active or scheduled.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CustomerPassResponse cancelSuspension(@PathVariable UUID customerId, @PathVariable UUID passId,
			@PathVariable UUID suspensionId, @Valid @RequestBody CancelSuspensionRequest request,
			Authentication authentication) {
		passes.cancelSuspension(customerId, passId, suspensionId, request.version(),
				CurrentAccount.of(authentication).id());
		return response(passes.get(customerId, passId));
	}

	private CustomerPassResponse response(CustomerPass pass) {
		return response(pass, passes.suspensionsOf(List.of(pass)).getOrDefault(pass.getId(), List.of()));
	}

	private CustomerPassResponse response(CustomerPass pass, List<PassSuspension> suspensions) {
		return CustomerPassResponse.from(pass, studio.currency(), suspensions, studio.today());
	}

}
