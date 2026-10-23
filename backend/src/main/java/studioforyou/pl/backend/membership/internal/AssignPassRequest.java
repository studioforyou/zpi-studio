package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import studioforyou.pl.backend.shared.api.Money;

/**
 * A pass the studio staff assign to a participant.
 *
 * @param variantId the active variant of an active pass product
 * @param validFrom the first day the pass is valid; a later date than today schedules the pass
 * @param pricePaid the price actually paid, in the variant's currency; the variant's price when missing
 * @param reason an optional note kept in the pass history
 */
@Schema(name = "AssignPassRequest")
public record AssignPassRequest(
		@NotNull UUID variantId,
		@Schema(example = "2026-11-01") @NotNull LocalDate validFrom,
		@Valid Money pricePaid,
		@Schema(example = "Karnet w prezencie od studia") @Size(max = 500) String reason) {

	CustomerPassService.Assignment toCommand() {
		return new CustomerPassService.Assignment(variantId, validFrom, (pricePaid != null) ? pricePaid.toAmount() : null,
				(pricePaid != null) ? pricePaid.currency() : null, reason);
	}

}
