package studioforyou.pl.backend.offering.internal.services;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import studioforyou.pl.backend.configuration.BookingPolicies;

@Schema(name = "ServiceType")
public record ServiceTypeResponse(UUID id, @Schema(example = "Pole dance - początkujący") String name,
		String description, @Schema(example = "GROUP_CLASS") String category,
		@Schema(example = "BEGINNER") String level,
		@Schema(description = "Youngest allowed age; null = no lower limit.", example = "16") Integer minAge,
		@Schema(description = "Oldest allowed age; null = no upper limit.") Integer maxAge,
		@Schema(example = "60") int defaultDurationMinutes,
		Integer minParticipants,
		@Schema(description = "Usual maximum number of participants; null = no limit.", example = "10")
		Integer maxParticipants,
		@Schema(description = "Calendar colour, #RRGGBB.") String calendarColor,
		@JsonProperty("isActive") boolean isActive, BookingPolicies.Definition bookingPolicy) {

	static ServiceTypeResponse from(ServiceType serviceType, BookingPolicies.Definition policy) {
		return new ServiceTypeResponse(serviceType.getId(), serviceType.getName(), serviceType.getDescription(),
				serviceType.getCategory(), serviceType.getLevel(), toInteger(serviceType.getMinAge()),
				toInteger(serviceType.getMaxAge()),
				serviceType.getDefaultDurationMinutes(), toInteger(serviceType.getMinParticipants()),
				toInteger(serviceType.getMaxParticipants()), serviceType.getCalendarColor(), serviceType.isActive(), policy);
	}

	private static Integer toInteger(Short value) {
		return (value != null) ? Integer.valueOf(value) : null;
	}

}
