package studioforyou.pl.backend.offering;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The terms of one pass product variant, for modules that sell or assign passes. A copy of
 * the values, so later changes to the offer do not change it.
 *
 * @param entriesCount number of entries, or {@code null} for unlimited entries
 * @param validityDays how many days a pass with these terms is valid
 * @param productActive whether the product is on sale (not archived)
 * @param variantActive whether the variant is on sale
 */
public record PassVariantTerms(UUID variantId, UUID productId, String productName, PassProductKind kind,
		PassProductAudience audience, Short entriesCount, short validityDays, BigDecimal price, String currency,
		boolean productActive, boolean variantActive) {
}
