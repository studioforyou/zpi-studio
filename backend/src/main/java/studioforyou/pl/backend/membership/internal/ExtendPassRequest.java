package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A later last day for a pass.
 *
 * @param validUntil the new last valid day, inclusive; later than the current one and not in the past
 * @param reason an optional note kept in the pass history
 * @param version the pass's {@code version}; a stale value returns 409 {@code VERSION_CONFLICT}
 */
@Schema(name = "ExtendPassRequest")
public record ExtendPassRequest(
		@Schema(example = "2026-12-15") @NotNull LocalDate validUntil,
		@Schema(example = "Kontuzja, uzgodnione z klientką") @Size(max = 500) String reason,
		@NotNull Long version) {

	CustomerPassService.Extension toCommand() {
		return new CustomerPassService.Extension(validUntil, reason, version);
	}

}
