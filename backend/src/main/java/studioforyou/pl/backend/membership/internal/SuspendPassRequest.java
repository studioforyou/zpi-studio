package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A period in which a pass cannot be used.
 *
 * @param startsOn the first suspended day; today or later, within the pass's validity
 * @param endsOn the last suspended day, inclusive; no later than the pass's last day
 * @param reason an optional note kept with the suspension and in the pass history
 * @param version the pass's {@code version}; a stale value returns 409 {@code VERSION_CONFLICT}
 */
@Schema(name = "SuspendPassRequest")
public record SuspendPassRequest(
		@Schema(example = "2026-11-10") @NotNull LocalDate startsOn,
		@Schema(example = "2026-11-16") @NotNull LocalDate endsOn,
		@Schema(example = "Wyjazd") @Size(max = 500) String reason,
		@NotNull Long version) {

	CustomerPassService.Suspension toCommand() {
		return new CustomerPassService.Suspension(startsOn, endsOn, reason, version);
	}

}
