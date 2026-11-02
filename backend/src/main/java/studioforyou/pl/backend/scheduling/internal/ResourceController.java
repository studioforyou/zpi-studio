package studioforyou.pl.backend.scheduling.internal;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.CreateResourceRequest;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.ResourceResponse;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.ServiceTypeRuleResponse;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.UpdateResourceRequest;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/resources")
@Tag(name = "scheduling")
class ResourceController {

	private final ResourceManagement resourceManagement;

	ResourceController(ResourceManagement resourceManagement) {
		this.resourceManagement = resourceManagement;
	}

	/** Open to trainers too, so that they can pick a room for the classes they add. */
	@GetMapping
	@PreAuthorize("hasAnyRole('ADMIN', 'TRAINER')")
	@Operation(summary = "List rooms", operationId = "listResources",
			description = "Available to administrators and trainers.")
	ListResponse<ResourceResponse> listResources(
			@Parameter(description = "Only active (true) or inactive (false) rooms; all when missing.")
			@RequestParam(required = false) @Nullable Boolean isActive) {
		return new ListResponse<>(this.resourceManagement.list(isActive));
	}

	@GetMapping("/{resourceId}")
	@Operation(summary = "Get a room", operationId = "getResource")
	@ApiResponse(responseCode = "200", description = "The room.")
	@ApiResponse(responseCode = "404", description = "The room does not exist.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ResourceResponse getResource(@PathVariable UUID resourceId) {
		return this.resourceManagement.get(resourceId);
	}

	@PostMapping
	@Operation(summary = "Create a room", operationId = "createResource")
	@ApiResponse(responseCode = "201", description = "The created room.")
	@ApiResponse(responseCode = "409", description = "RESOURCE_NAME_ALREADY_USED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "RESOURCE_PARENT_INVALID or SERVICE_TYPE_UNAVAILABLE",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<ResourceResponse> createResource(@Valid @RequestBody CreateResourceRequest request) {
		ResourceResponse created = this.resourceManagement.create(request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{resourceId}")
			.buildAndExpand(created.id())
			.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@PatchMapping("/{resourceId}")
	@Operation(summary = "Update or deactivate a room", operationId = "updateResource")
	@ApiResponse(responseCode = "200", description = "The updated room.")
	@ApiResponse(responseCode = "404", description = "The room does not exist.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "RESOURCE_NAME_ALREADY_USED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "RESOURCE_PARENT_INVALID or SERVICE_TYPE_UNAVAILABLE",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResourceResponse updateResource(@PathVariable UUID resourceId,
			@Valid @RequestBody UpdateResourceRequest request) {
		return this.resourceManagement.update(resourceId, request);
	}

	@GetMapping("/{resourceId}/service-types")
	@Operation(summary = "List the service types that may use a room", operationId = "listResourceServiceTypes")
	@ApiResponse(responseCode = "200", description = "Service types allowed to use the room.")
	@ApiResponse(responseCode = "404", description = "The room does not exist.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ListResponse<ServiceTypeRuleResponse> listResourceServiceTypes(@PathVariable UUID resourceId) {
		return new ListResponse<>(this.resourceManagement.listRules(resourceId));
	}

}
