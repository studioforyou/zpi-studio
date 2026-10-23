package studioforyou.pl.backend.offering.internal;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import studioforyou.pl.backend.offering.PassProductAudience;
import studioforyou.pl.backend.offering.PassProductKind;
import studioforyou.pl.backend.offering.internal.PassProductCatalog.NewProduct;

/**
 * A new pass product with at least one variant.
 *
 * @param suspensionAllowed whether the pass can be suspended; {@code false} when missing
 * @param maxSuspensionDays the longest allowed suspension, or {@code null} without a limit; ignored without suspension
 * @param serviceTypeIds the active services the product pays for
 */
@Schema(name = "CreatePassProductRequest")
public record CreatePassProductRequest(
		@Schema(example = "Karnet Pole Dance") @NotBlank @Size(max = 120) String name,
		String description,
		@NotNull PassProductKind kind,
		@NotNull PassProductAudience audience,
		Boolean suspensionAllowed,
		@Positive Short maxSuspensionDays,
		@NotNull Set<@NotNull UUID> serviceTypeIds,
		@NotEmpty List<@NotNull @Valid PassProductVariantRequest> variants) {

	NewProduct toCommand() {
		return new NewProduct(name, description, kind, audience, Boolean.TRUE.equals(suspensionAllowed),
				maxSuspensionDays, serviceTypeIds,
				variants.stream().map(PassProductVariantRequest::toCommand).toList());
	}

}
