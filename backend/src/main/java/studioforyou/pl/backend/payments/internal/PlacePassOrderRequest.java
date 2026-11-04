package studioforyou.pl.backend.payments.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * An order for one pass. The pass starts on {@code startsOn}, or right after another pass of
 * the participant ends ({@code startAfterPassId}); exactly one of them is given.
 *
 * @param variantId the active variant of an active pass product
 * @param startsOn the first day of the pass, today or later
 * @param startAfterPassId a pass of the participant the new one follows: it starts the day after
 * that pass's last day, or today if that pass has already ended
 * @param termsAccepted the client accepted the terms of sale; must be {@code true}
 */
@Schema(name = "PlacePassOrderRequest")
public record PlacePassOrderRequest(
		@NotNull UUID variantId,
		@Schema(example = "2026-11-20") LocalDate startsOn,
		UUID startAfterPassId,
		@Schema(example = "true") @NotNull Boolean termsAccepted) {

	PassOrderService.NewPassOrder toCommand() {
		return new PassOrderService.NewPassOrder(variantId, startsOn, startAfterPassId, termsAccepted);
	}

}
