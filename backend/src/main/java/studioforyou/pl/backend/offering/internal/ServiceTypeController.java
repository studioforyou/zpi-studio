package studioforyou.pl.backend.offering.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only list of service types, e.g. to choose the services a pass product pays for.
 */
@RestController
@RequestMapping("/api/v1/service-types")
@Tag(name = "offering")
public class ServiceTypeController {

	private final ServiceTypeRepository serviceTypes;

	public ServiceTypeController(ServiceTypeRepository serviceTypes) {
		this.serviceTypes = serviceTypes;
	}

	@GetMapping
	@Operation(summary = "List service types", operationId = "listServiceTypes")
	public ServiceTypeListResponse list(
			@Parameter(description = "true for active service types, false for inactive ones; all when omitted.")
			@RequestParam(required = false) Boolean active) {
		var items = (active != null) ? serviceTypes.findByActiveOrderByName(active)
				: serviceTypes.findAllByOrderByName();
		return new ServiceTypeListResponse(items.stream().map(ServiceTypeResponse::from).toList());
	}

}
