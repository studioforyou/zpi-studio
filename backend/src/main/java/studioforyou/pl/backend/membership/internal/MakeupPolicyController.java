package studioforyou.pl.backend.membership.internal;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;

/**
 * Make-up policies the studio attaches to pass products. Only administrators manage them.
 * A change applies to credits granted from then on; credits already granted keep their expiry.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/makeup-policies")
@Tag(name = "membership")
public class MakeupPolicyController {

	private final MakeupPolicyManagement policies;

	MakeupPolicyController(MakeupPolicyManagement policies) {
		this.policies = policies;
	}

	@GetMapping
	@Operation(summary = "List make-up policies", operationId = "listMakeupPolicies",
			description = "Active policies first, then by name. productCount counts the active products using a policy.")
	public ListResponse<MakeupPolicyResponse> list(
			@Parameter(description = "true for active policies, false for inactive ones; all when omitted.")
			@RequestParam(required = false) Boolean active) {
		return new ListResponse<>(policies.list(active));
	}

	@GetMapping("/{policyId}")
	@Operation(summary = "Get a make-up policy", operationId = "getMakeupPolicy")
	@ApiResponse(responseCode = "200", description = "The policy.")
	@ApiResponse(responseCode = "404", description = "No such policy.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public MakeupPolicyResponse get(@PathVariable UUID policyId) {
		return policies.get(policyId);
	}

	@PostMapping
	@Operation(summary = "Create a make-up policy", operationId = "createMakeupPolicy")
	@ApiResponse(responseCode = "201", description = "The created policy.")
	@ApiResponse(responseCode = "400", description = "Validation failed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<MakeupPolicyResponse> create(@Valid @RequestBody MakeupPolicyRequest request) {
		MakeupPolicyResponse policy = policies.create(request);
		return ResponseEntity.created(URI.create("/api/v1/makeup-policies/" + policy.id())).body(policy);
	}

	@PutMapping("/{policyId}")
	@Operation(summary = "Replace the terms of a make-up policy", operationId = "updateMakeupPolicy",
			description = "Applies to credits granted from now on. Credits already granted keep their expiry.")
	@ApiResponse(responseCode = "200", description = "The updated policy.")
	@ApiResponse(responseCode = "400", description = "Validation failed.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such policy.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public MakeupPolicyResponse update(@PathVariable UUID policyId, @Valid @RequestBody MakeupPolicyRequest request) {
		return policies.update(policyId, request);
	}

	@PostMapping("/{policyId}/deactivate")
	@Operation(summary = "Deactivate a make-up policy", operationId = "deactivateMakeupPolicy",
			description = "Products using it stop granting new credits. Credits already granted stay valid.")
	@ApiResponse(responseCode = "200", description = "The deactivated policy.")
	@ApiResponse(responseCode = "404", description = "No such policy.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public MakeupPolicyResponse deactivate(@PathVariable UUID policyId) {
		return policies.setActive(policyId, false);
	}

	@PostMapping("/{policyId}/activate")
	@Operation(summary = "Activate a make-up policy again", operationId = "activateMakeupPolicy")
	@ApiResponse(responseCode = "200", description = "The activated policy.")
	@ApiResponse(responseCode = "404", description = "No such policy.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public MakeupPolicyResponse activate(@PathVariable UUID policyId) {
		return policies.setActive(policyId, true);
	}

}
