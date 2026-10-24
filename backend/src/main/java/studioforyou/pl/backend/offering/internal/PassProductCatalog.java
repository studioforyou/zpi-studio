package studioforyou.pl.backend.offering.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.Hibernate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.offering.PassProductAudience;
import studioforyou.pl.backend.offering.PassProductKind;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Defines pass products: their variants (entries x validity x price) and the services they pay for.
 * Variants are never edited: a new price creates a new variant and deactivates the old one, so
 * the price list keeps its history.
 */
@Service
@Transactional
public class PassProductCatalog {

	private final PassProductRepository products;

	private final ServiceTypeRepository serviceTypes;

	private final Clock clock = Clock.systemUTC();

	public PassProductCatalog(PassProductRepository products, ServiceTypeRepository serviceTypes) {
		this.products = products;
		this.serviceTypes = serviceTypes;
	}

	/**
	 * @param active {@code true} for active products, {@code false} for archived ones, {@code null} for all
	 */
	@Transactional(readOnly = true)
	public List<PassProduct> list(Boolean active) {
		List<PassProduct> result = (active != null) ? products.findByActiveOrderByName(active)
				: products.findAllByOrderByName();
		result.forEach(PassProductCatalog::loadCollections);
		return result;
	}

	@Transactional(readOnly = true)
	public PassProduct get(UUID productId) {
		PassProduct product = products.findById(productId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass product not found."));
		loadCollections(product);
		return product;
	}

	public PassProduct create(NewProduct command) {
		requireAvailableServiceTypes(Set.of(), command.serviceTypeIds());
		PassProduct product = new PassProduct(command.name(), blankToNull(command.description()), command.kind(),
				command.audience());
		product.setSuspension(command.suspensionAllowed(), command.maxSuspensionDays());
		product.setServiceTypeIds(command.serviceTypeIds());
		Instant now = clock.instant();
		for (NewVariant variant : command.variants()) {
			addVariant(product, variant, now);
		}
		return products.saveAndFlush(product);
	}

	/**
	 * Changes the given details; {@code null} values leave a detail unchanged and a blank
	 * description removes it.
	 */
	public PassProduct update(UUID productId, ProductChanges changes) {
		PassProduct product = get(productId);
		if (changes.name() != null) {
			product.setName(changes.name());
		}
		if (changes.description() != null) {
			product.setDescription(blankToNull(changes.description()));
		}
		if (changes.audience() != null) {
			product.setAudience(changes.audience());
		}
		if (changes.suspensionAllowed() != null || changes.maxSuspensionDays() != null) {
			boolean allowed = (changes.suspensionAllowed() != null) ? changes.suspensionAllowed()
					: product.isSuspensionAllowed();
			Short maxDays = (changes.maxSuspensionDays() != null) ? changes.maxSuspensionDays()
					: product.getMaxSuspensionDays();
			product.setSuspension(allowed, maxDays);
		}
		if (changes.serviceTypeIds() != null) {
			requireAvailableServiceTypes(product.getServiceTypeIds(), changes.serviceTypeIds());
			product.setServiceTypeIds(changes.serviceTypeIds());
		}
		products.flush();
		return product;
	}

	/** Takes the product off sale. Passes already bought keep working. */
	public PassProduct archive(UUID productId) {
		PassProduct product = get(productId);
		product.setActive(false);
		products.flush();
		return product;
	}

	public PassProduct restore(UUID productId) {
		PassProduct product = get(productId);
		product.setActive(true);
		products.flush();
		return product;
	}

	public PassProductVariant addVariant(UUID productId, NewVariant command) {
		PassProduct product = get(productId);
		requireActive(product);
		PassProductVariant variant = addVariant(product, command, clock.instant());
		products.flush();
		return variant;
	}

	public PassProductVariant deactivateVariant(UUID productId, UUID variantId) {
		PassProductVariant variant = getVariant(productId, variantId);
		variant.deactivate(clock.instant());
		products.flush();
		return variant;
	}

	/**
	 * Replaces an active variant with a new one with the same entries and validity and the new
	 * price. Orders and passes already bought keep their own copy of the old price.
	 */
	public PassProductVariant changePrice(UUID productId, UUID variantId, BigDecimal price, String currency) {
		PassProductVariant current = getVariant(productId, variantId);
		PassProduct product = current.getProduct();
		requireActive(product);
		if (!current.isActive()) {
			throw new ApiException(ErrorCode.PASS_VARIANT_INACTIVE, "Only an active variant can get a new price.");
		}
		Instant now = clock.instant();
		current.deactivate(now);
		// Only one active variant with the same terms is allowed, so the old one is
		// deactivated in the database before the new one is inserted.
		products.flush();
		PassProductVariant replacement = addVariant(product,
				new NewVariant(current.getEntriesCount(), current.getValidityDays(), price, currency), now);
		products.flush();
		return replacement;
	}

	@Transactional(readOnly = true)
	public PassProductVariant getVariant(UUID productId, UUID variantId) {
		return get(productId).getVariants()
			.stream()
			.filter((variant) -> variant.getId().equals(variantId))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass product variant not found."));
	}

	private PassProductVariant addVariant(PassProduct product, NewVariant command, Instant now) {
		boolean duplicate = product.getVariants()
			.stream()
			.anyMatch((variant) -> variant.isActive()
					&& variant.hasSameTerms(command.entriesCount(), command.validityDays()));
		if (duplicate) {
			throw new ApiException(ErrorCode.PASS_VARIANT_ALREADY_EXISTS,
					"The product already has an active variant with the same entries and validity.");
		}
		PassProductVariant variant = new PassProductVariant(product, command.entriesCount(), command.validityDays(),
				command.price(), command.currency(), now);
		product.addVariant(variant);
		return variant;
	}

	/**
	 * Loads the lazy collections while the transaction is open: responses are built after it
	 * ends ({@code spring.jpa.open-in-view} is off).
	 */
	private static void loadCollections(PassProduct product) {
		Hibernate.initialize(product.getVariants());
		Hibernate.initialize(product.getServiceTypeIds());
	}

	private static void requireActive(PassProduct product) {
		if (!product.isActive()) {
			throw new ApiException(ErrorCode.PASS_PRODUCT_ARCHIVED, "The pass product is archived.");
		}
	}

	/**
	 * Services newly linked to a product must exist and be active; services already linked
	 * may stay even if they were deactivated since.
	 */
	private void requireAvailableServiceTypes(Set<UUID> current, Set<UUID> requested) {
		Set<UUID> added = new HashSet<>(requested);
		added.removeAll(current);
		if (!added.isEmpty() && serviceTypes.countByIdInAndActiveTrue(added) != added.size()) {
			throw new ApiException(ErrorCode.SERVICE_TYPE_UNAVAILABLE,
					"Every service type must exist and be active.");
		}
	}

	private static String blankToNull(String text) {
		return (text == null || text.isBlank()) ? null : text;
	}

	public record NewProduct(String name, String description, PassProductKind kind, PassProductAudience audience,
			boolean suspensionAllowed, Short maxSuspensionDays, Set<UUID> serviceTypeIds, List<NewVariant> variants) {
	}

	/**
	 * @param entriesCount number of entries, or {@code null} for unlimited entries
	 */
	public record NewVariant(Short entriesCount, short validityDays, BigDecimal price, String currency) {
	}

	public record ProductChanges(String name, String description, PassProductAudience audience,
			Boolean suspensionAllowed, Short maxSuspensionDays, Set<UUID> serviceTypeIds) {
	}

}
