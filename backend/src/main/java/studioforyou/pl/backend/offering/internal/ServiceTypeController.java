package studioforyou.pl.backend.offering.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.shared.api.ListResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/service-types")
@Tag(name = "offering")
class ServiceTypeController {

	private final ServiceTypes serviceTypes;

	ServiceTypeController(ServiceTypes serviceTypes) {
		this.serviceTypes = serviceTypes;
	}

	// TODO(auth): requires authentication once authentication is implemented
	// TODO(offering): minimal read-only list; the full service catalogue API belongs to the offering task
	@GetMapping
	@Operation(summary = "List active service types", operationId = "listServiceTypes")
	ListResponse<ServiceTypeResponse> listServiceTypes() {
		return new ListResponse<>(this.serviceTypes.findActive().stream().map(ServiceTypeResponse::from).toList());
	}

	@Schema(name = "ServiceType", description = "A service offered by the studio.")
	record ServiceTypeResponse(UUID id, @Schema(example = "Pole dance, beginners") String name) {

		static ServiceTypeResponse from(ServiceTypeSummary summary) {
			return new ServiceTypeResponse(summary.id(), summary.name());
		}

	}

}
