package studioforyou.pl.backend.membership.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.membership.CustomerPassStatus;
import studioforyou.pl.backend.membership.internal.CustomerPass;
import studioforyou.pl.backend.shared.api.Money;

/**
 * A participant's pass with the terms copied when it was assigned or bought.
 *
 * @param productName the product's name at the time of assignment
 * @param entriesTotal number of entries, or {@code null} for unlimited entries
 * @param entriesRemaining entries left, or {@code null} for unlimited entries
 * @param validUntil the last day the pass is valid, inclusive
 * @param orderItemId the order line the pass was bought with, or {@code null} if the staff assigned it
 * @param createdBy the account that assigned the pass
 * @param version send it back with changes; a stale value returns 409 {@code VERSION_CONFLICT}
 */
@Schema(name = "CustomerPass")
public record CustomerPassResponse(UUID id, UUID customerId, UUID variantId,
		@Schema(example = "Karnet Pole Dance") String productName, @Schema(example = "8") Short entriesTotal,
		@Schema(example = "8") Short entriesRemaining, Money pricePaid, CustomerPassStatus status, LocalDate validFrom,
		LocalDate validUntil, UUID orderItemId, UUID createdBy, Instant createdAt, Instant updatedAt, int version) {

	/**
	 * @param currency the currency of the price; {@code customer_pass} keeps only the amount
	 */
	static CustomerPassResponse from(CustomerPass pass, String currency) {
		return new CustomerPassResponse(pass.getId(), pass.getCustomerId(), pass.getVariantId(),
				pass.getProductName(), pass.getEntriesTotal(), pass.getEntriesRemaining(),
				Money.of(pass.getPricePaid(), currency), pass.getStatus(), pass.getValidFrom(), pass.getValidUntil(),
				pass.getOrderItemId(), pass.getCreatedBy(), pass.getCreatedAt(), pass.getUpdatedAt(),
				pass.getVersion());
	}

}
