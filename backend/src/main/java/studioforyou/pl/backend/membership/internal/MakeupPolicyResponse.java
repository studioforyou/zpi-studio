package studioforyou.pl.backend.membership.internal;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A make-up policy with the number of products on sale that use it.
 *
 * @param maxPerPass the most credits one pass can give, or {@code null} without a limit
 * @param productCount active pass products that grant credits under this policy
 */
@Schema(name = "MakeupPolicy")
public record MakeupPolicyResponse(UUID id, @Schema(example = "Odrabianie 30 dni") String name, int validityDays,
		Integer maxPerPass, boolean grantOnInTimeCancel, boolean grantOnStudioCancel,
		MakeupExpiryCheck expiryCheckedAgainst, @JsonProperty("isActive") boolean isActive, Instant createdAt,
		long productCount) {

}
