package studioforyou.pl.backend.offering.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.offering.PassProductAudience;
import studioforyou.pl.backend.offering.PassProductKind;

/**
 * A pass product with its variants and the services it pays for.
 *
 * @param maxSuspensionDays the longest allowed suspension, or {@code null} without a limit or without suspension
 * @param isActive whether the product is on sale; archived products are only visible to administrators
 * @param serviceTypeIds the services the product pays for
 * @param variants the product's variants; clients other than administrators get only the active ones
 */
@Schema(name = "PassProduct")
public record PassProductResponse(UUID id, @Schema(example = "Karnet Pole Dance") String name, String description,
		PassProductKind kind, PassProductAudience audience, boolean suspensionAllowed, Short maxSuspensionDays,
		@JsonProperty("isActive") boolean isActive, List<UUID> serviceTypeIds, List<PassProductVariantResponse> variants,
		Instant createdAt, Instant updatedAt) {

	static PassProductResponse from(PassProduct product, boolean withInactiveVariants) {
		List<PassProductVariantResponse> variants = product.getVariants()
			.stream()
			.filter((variant) -> withInactiveVariants || variant.isActive())
			.map(PassProductVariantResponse::from)
			.toList();
		List<UUID> serviceTypeIds = product.getServiceTypeIds().stream().sorted().toList();
		return new PassProductResponse(product.getId(), product.getName(), product.getDescription(),
				product.getKind(), product.getAudience(), product.isSuspensionAllowed(),
				product.getMaxSuspensionDays(), product.isActive(), serviceTypeIds, variants, product.getCreatedAt(),
				product.getUpdatedAt());
	}

}
