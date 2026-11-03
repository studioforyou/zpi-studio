package studioforyou.pl.backend.membership.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A suspension of a pass that was not cancelled.
 *
 * @param endsOn the last suspended day, inclusive
 * @param createdBy the account that suspended the pass
 */
@Schema(name = "PassSuspension")
public record PassSuspensionResponse(UUID id, LocalDate startsOn, LocalDate endsOn,
		@Schema(example = "Wyjazd") String reason, UUID createdBy, Instant createdAt) {

	static PassSuspensionResponse from(PassSuspension suspension) {
		return new PassSuspensionResponse(suspension.getId(), suspension.getStartsOn(), suspension.getEndsOn(),
				suspension.getReason(), suspension.getCreatedBy(), suspension.getCreatedAt());
	}

}
