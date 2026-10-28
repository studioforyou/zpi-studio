package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.membership.CustomerPassStatus;

/**
 * A pass as its participant (or the participant's guardian) sees it.
 *
 * @param productName the product's name at the time the pass was assigned or bought
 * @param entriesTotal number of entries, or {@code null} for unlimited entries
 * @param entriesRemaining entries left, or {@code null} for unlimited entries
 * @param status the status today in the studio's time zone: {@code SCHEDULED} before
 * {@code validFrom}, {@code EXPIRED} after {@code validUntil}
 * @param validUntil the last day the pass is valid, inclusive
 */
@Schema(name = "OwnPass")
public record OwnPassResponse(UUID id, UUID customerId, @Schema(example = "Karnet Pole Dance") String productName,
		@Schema(example = "8") Short entriesTotal, @Schema(example = "5") Short entriesRemaining,
		CustomerPassStatus status, LocalDate validFrom, LocalDate validUntil) {

	/**
	 * @param today today's date in the studio's time zone
	 */
	static OwnPassResponse from(CustomerPass pass, LocalDate today) {
		return new OwnPassResponse(pass.getId(), pass.getCustomerId(), pass.getProductName(), pass.getEntriesTotal(),
				pass.getEntriesRemaining(), pass.statusOn(today), pass.getValidFrom(), pass.getValidUntil());
	}

}
