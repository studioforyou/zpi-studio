package studioforyou.pl.backend.offering;

import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.offering.internal.PassProduct;
import studioforyou.pl.backend.offering.internal.PassProductVariantRepository;

/**
 * Read access to the terms of pass product variants for other modules.
 */
@Service
@Transactional(readOnly = true)
public class PassVariantLookup {

	private final PassProductVariantRepository variants;

	public PassVariantLookup(PassProductVariantRepository variants) {
		this.variants = variants;
	}

	public Optional<PassVariantTerms> findTerms(UUID variantId) {
		return variants.findWithProductById(variantId).map((variant) -> {
			PassProduct product = variant.getProduct();
			return new PassVariantTerms(variant.getId(), product.getId(), product.getName(), product.getKind(),
					product.getAudience(), variant.getEntriesCount(), variant.getValidityDays(), variant.getPrice(),
					variant.getCurrency(), product.isActive(), variant.isActive(), product.isSuspensionAllowed(),
					product.getMaxSuspensionDays());
		});
	}

	/** Product labels for historical variants too; no sale/active restriction. */
	public Map<UUID, ProductReference> productsOfVariants(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return variants.findWithProductsByIdIn(ids).stream().collect(Collectors.toMap(
			variant -> variant.getId(), variant -> new ProductReference(variant.getProduct().getId(),
					variant.getProduct().getName())));
	}

	public record ProductReference(UUID id, String name) {
	}

	/**
	 * Whether the variant's product pays for this service. Archived products and replaced
	 * price variants keep covering services for passes already held by participants.
	 */
	public boolean coversService(UUID variantId, UUID serviceTypeId) {
		return variants.coversService(variantId, serviceTypeId);
	}

}
