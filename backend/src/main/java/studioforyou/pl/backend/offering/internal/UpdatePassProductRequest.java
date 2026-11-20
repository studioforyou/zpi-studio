package studioforyou.pl.backend.offering.internal;

import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import studioforyou.pl.backend.offering.PassProductAudience;
import studioforyou.pl.backend.offering.internal.PassProductCatalog.ProductChanges;

/**
 * Partial update of a pass product: {@code null} or missing fields stay unchanged. The kind
 * cannot change; prices change through the variants.
 *
 * @param description new description; an empty text removes it
 * @param suspensionAllowed whether the pass can be suspended; {@code false} also removes the day limit
 * @param serviceTypeIds the full new list of services the product pays for
 * @param makeupPolicyId a new active make-up policy for the product
 * @param removeMakeupPolicy {@code true} to stop granting make-up credits; takes precedence over {@code makeupPolicyId}
 */
@Schema(name = "UpdatePassProductRequest")
public record UpdatePassProductRequest(
		@Size(max = 120) @Pattern(regexp = ".*\\S.*") String name,
		String description,
		PassProductAudience audience,
		Boolean suspensionAllowed,
		@Positive Short maxSuspensionDays,
		Set<@NotNull UUID> serviceTypeIds,
		UUID makeupPolicyId,
		Boolean removeMakeupPolicy) {

	ProductChanges toChanges() {
		return new ProductChanges(name, description, audience, suspensionAllowed, maxSuspensionDays, serviceTypeIds,
				makeupPolicyId, Boolean.TRUE.equals(removeMakeupPolicy));
	}

}
