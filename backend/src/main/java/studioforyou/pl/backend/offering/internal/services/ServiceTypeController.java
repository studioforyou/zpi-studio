package studioforyou.pl.backend.offering.internal.services;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.offering.internal.services.ServiceTypeRequests.CreateServiceTypeRequest;
import studioforyou.pl.backend.offering.internal.services.ServiceTypeRequests.UpdateServiceTypeRequest;
import studioforyou.pl.backend.shared.api.AccountAuthentication;

@RestController
@RequestMapping("/api/v1/service-types")
@Tag(name = "offering")
public class ServiceTypeController {

	private final ServiceTypeManagement serviceTypes;

	public ServiceTypeController(ServiceTypeManagement serviceTypes) {
		this.serviceTypes = serviceTypes;
	}

	@GetMapping
	@Operation(summary = "List service types", operationId = "listServiceTypes")
	public ServiceTypeListResponse list(
			@Parameter(description = "true for active service types, false for inactive ones; all when omitted.")
			@RequestParam(required = false) Boolean active) {
		return new ServiceTypeListResponse(this.serviceTypes.list(active));
	}

	@GetMapping("/{serviceTypeId}")
	@Operation(summary = "Get a service type", operationId = "getServiceType")
	@ApiResponse(responseCode = "200", description = "The service definition and participation rules.")
	@ApiResponse(responseCode = "404", description = "Service type not found.")
	public ServiceTypeResponse get(@PathVariable UUID serviceTypeId) {
		return this.serviceTypes.get(serviceTypeId);
	}

	@GetMapping("/booking-defaults")
	@Operation(summary = "Read studio defaults for service booking rules", operationId = "getServiceBookingDefaults")
	public BookingPolicies.Values bookingDefaults() {
		return this.serviceTypes.bookingDefaults();
	}

	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	@ApiResponse(responseCode = "201", description = "The created service type.")
	@ApiResponse(responseCode = "400", description = "Invalid service definition or participation rules.")
	@Operation(summary = "Create a service type", operationId = "createServiceType",
			description = "Administrators define the service and its participation rules.")
	public ResponseEntity<ServiceTypeResponse> create(AccountAuthentication authentication,
			@Valid @RequestBody CreateServiceTypeRequest request) {
		ServiceTypeResponse service = this.serviceTypes.create(request, accountId(authentication));
		return ResponseEntity.created(URI.create("/api/v1/service-types/" + service.id())).body(service);
	}

	@PatchMapping("/{serviceTypeId}")
	@PreAuthorize("hasRole('ADMIN')")
	@ApiResponse(responseCode = "200", description = "The saved service type.")
	@ApiResponse(responseCode = "400", description = "Invalid service definition or participation rules.")
	@ApiResponse(responseCode = "404", description = "Service type not found.")
	@Operation(summary = "Update a service type", operationId = "updateServiceType",
			description = "Missing fields stay unchanged. Null clears optional fields. Set isActive to deactivate or restore a definition; existing references remain. "
					+ "bookingPolicy replaces the service's explicit booking rules; null restores studio defaults. Rule changes create a new policy version. "
					+ "Personal training supports a configurable participant capacity and does not use attendance-based cancellation or waitlists. These operations configure rules without cancelling reservations or events.")
	public ServiceTypeResponse update(AccountAuthentication authentication, @PathVariable UUID serviceTypeId,
			@Valid @RequestBody UpdateServiceTypeRequest request) {
		return this.serviceTypes.update(serviceTypeId, request, accountId(authentication));
	}

	private static UUID accountId(AccountAuthentication authentication) {
		return authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
	}

}
