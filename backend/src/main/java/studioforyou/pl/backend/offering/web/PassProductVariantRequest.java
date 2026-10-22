package studioforyou.pl.backend.offering.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import studioforyou.pl.backend.offering.internal.PassProductCatalog.NewVariant;
import studioforyou.pl.backend.shared.api.Money;

/**
 * A new variant of a pass product.
 *
 * @param entriesCount number of entries, or {@code null} for unlimited entries
 * @param validityDays how many days a bought pass is valid
 */
@Schema(name = "PassProductVariantRequest")
public record PassProductVariantRequest(
		@Schema(example = "8") @Positive Short entriesCount,
		@Schema(example = "30") @NotNull @Positive Short validityDays,
		@NotNull @Valid Money price) {

	NewVariant toCommand() {
		return new NewVariant(entriesCount, validityDays, price.toAmount(), price.currency());
	}

}
