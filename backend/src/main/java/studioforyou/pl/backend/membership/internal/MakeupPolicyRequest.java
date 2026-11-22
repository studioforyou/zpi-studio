package studioforyou.pl.backend.membership.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * All terms of a make-up policy, for creating one or replacing the terms of an existing one.
 *
 * @param validityDays how many days a granted credit stays valid
 * @param maxPerPass the most credits one pass can give, or {@code null} without a limit
 * @param grantOnInTimeCancel whether a cancellation before the free cancellation deadline gives a credit
 * @param grantOnStudioCancel whether a class cancelled by the studio gives a credit
 */
@Schema(name = "MakeupPolicyRequest")
public record MakeupPolicyRequest(
		@Schema(example = "Odrabianie 30 dni") @NotBlank @Size(max = 120) String name,
		@NotNull @Positive @Max(3650) Integer validityDays,
		@Positive @Max(1000) Integer maxPerPass,
		@NotNull Boolean grantOnInTimeCancel,
		@NotNull Boolean grantOnStudioCancel,
		@NotNull MakeupExpiryCheck expiryCheckedAgainst) {

}
