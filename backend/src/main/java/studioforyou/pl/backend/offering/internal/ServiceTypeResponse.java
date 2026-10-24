package studioforyou.pl.backend.offering.internal;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ServiceType")
public record ServiceTypeResponse(UUID id, @Schema(example = "Pole dance - początkujący") String name,
		@Schema(example = "GROUP_CLASS") String category, @Schema(example = "BEGINNER") String level,
		@Schema(description = "Youngest allowed age; null = no lower limit.", example = "16") Integer minAge,
		@Schema(description = "Oldest allowed age; null = no upper limit.") Integer maxAge,
		@Schema(example = "60") int defaultDurationMinutes,
		@Schema(description = "Usual maximum number of participants; null = no limit.", example = "10")
		Integer maxParticipants,
		@Schema(description = "Calendar colour, #RRGGBB.", example = "#E91E63") String calendarColor,
		@JsonProperty("isActive") boolean isActive) {

	static ServiceTypeResponse from(ServiceType serviceType) {
		return new ServiceTypeResponse(serviceType.getId(), serviceType.getName(), serviceType.getCategory(),
				serviceType.getLevel(), toInteger(serviceType.getMinAge()), toInteger(serviceType.getMaxAge()),
				serviceType.getDefaultDurationMinutes(), toInteger(serviceType.getMaxParticipants()),
				serviceType.getCalendarColor(), serviceType.isActive());
	}

	private static Integer toInteger(Short value) {
		return (value != null) ? Integer.valueOf(value) : null;
	}

}
