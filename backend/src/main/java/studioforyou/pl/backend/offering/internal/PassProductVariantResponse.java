package studioforyou.pl.backend.offering.internal;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.shared.api.Money;

/**
 * One price variant of a pass product.
 *
 * @param entriesCount number of entries, or {@code null} for unlimited entries
 * @param validityDays how many days a bought pass is valid
 * @param validTo when the variant was taken off sale, or {@code null} while it is active
 */
@Schema(name = "PassProductVariant")
public record PassProductVariantResponse(UUID id, @Schema(example = "8") Short entriesCount,
		@Schema(example = "30") short validityDays, Money price, @JsonProperty("isActive") boolean isActive,
		Instant validFrom, Instant validTo) {

	static PassProductVariantResponse from(PassProductVariant variant) {
		return new PassProductVariantResponse(variant.getId(), variant.getEntriesCount(), variant.getValidityDays(),
				Money.of(variant.getPrice(), variant.getCurrency()), variant.isActive(), variant.getValidFrom(),
				variant.getValidTo());
	}

}
