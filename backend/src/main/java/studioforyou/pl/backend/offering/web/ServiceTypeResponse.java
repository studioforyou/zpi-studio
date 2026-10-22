package studioforyou.pl.backend.offering.web;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.offering.internal.ServiceType;

@Schema(name = "ServiceType")
public record ServiceTypeResponse(UUID id, @Schema(example = "Pole dance - początkujący") String name,
		@Schema(example = "GROUP_CLASS") String category, @Schema(example = "BEGINNER") String level,
		@JsonProperty("isActive") boolean isActive) {

	static ServiceTypeResponse from(ServiceType serviceType) {
		return new ServiceTypeResponse(serviceType.getId(), serviceType.getName(), serviceType.getCategory(),
				serviceType.getLevel(), serviceType.isActive());
	}

}
