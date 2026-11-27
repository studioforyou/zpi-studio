package studioforyou.pl.backend.customer.internal;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** The service types a participant wants to hear about when booking of their classes opens. */
@Schema(name = "CustomerInterests")
public record CustomerInterestsResponse(
		@Schema(description = "Marked service types, oldest first.") List<UUID> serviceTypeIds) {
}
