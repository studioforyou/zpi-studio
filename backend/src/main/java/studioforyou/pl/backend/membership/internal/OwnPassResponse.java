package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.membership.CustomerPassStatus;

/**
 * A pass as its participant (or the participant's guardian) sees it.
 *
 * @param variantId the variant the pass was assigned or bought with, to buy the same one again
 * @param productName the product's name at the time the pass was assigned or bought
 * @param entriesTotal number of entries, or {@code null} for unlimited entries
 * @param entriesRemaining entries left, or {@code null} for unlimited entries
 * @param status the status today in the studio's time zone: {@code SCHEDULED} before
 * {@code validFrom}, {@code SUSPENDED} during a suspension, {@code EXPIRED} after {@code validUntil}
 * @param validUntil the last day the pass is valid, inclusive
 * @param expiringSoon whether a usable pass is about to run out: at most {@value #EXPIRING_WITHIN_DAYS}
 * days left (counting today and the last day) or at most {@value #EXPIRING_WITH_ENTRIES} entries left
 */
@Schema(name = "OwnPass")
public record OwnPassResponse(UUID id, UUID customerId, UUID variantId,
		@Schema(example = "Karnet Pole Dance") String productName, @Schema(example = "8") Short entriesTotal,
		@Schema(example = "5") Short entriesRemaining, CustomerPassStatus status, LocalDate validFrom,
		LocalDate validUntil, boolean expiringSoon) {

	/** A usable pass with at most this many days left, counting today and the last day, is expiring soon. */
	static final int EXPIRING_WITHIN_DAYS = 7;

	/** A usable pass with at most this many entries left is expiring soon. */
	static final int EXPIRING_WITH_ENTRIES = 2;

	/**
	 * @param suspensions the pass's suspensions that were not cancelled
	 * @param suspensions the pass's suspensions that were not cancelled
	 * @param today today's date in the studio's time zone
	 */
	static OwnPassResponse from(CustomerPass pass, List<PassSuspension> suspensions, LocalDate today) {
		boolean suspended = suspensions.stream().anyMatch((suspension) -> suspension.includes(today));
		CustomerPassStatus status = pass.statusOn(today, suspended);
		return new OwnPassResponse(pass.getId(), pass.getCustomerId(), pass.getVariantId(), pass.getProductName(),
				pass.getEntriesTotal(), pass.getEntriesRemaining(), status, pass.getValidFrom(), pass.getValidUntil(),
				expiringSoon(pass, status, today));
	}

	private static boolean expiringSoon(CustomerPass pass, CustomerPassStatus status, LocalDate today) {
		if (status != CustomerPassStatus.ACTIVE && status != CustomerPassStatus.SUSPENDED) {
			return false;
		}
		boolean endsSoon = pass.getValidUntil() != null
				&& pass.getValidUntil().isBefore(today.plusDays(EXPIRING_WITHIN_DAYS));
		boolean fewEntries = pass.getEntriesRemaining() != null
				&& pass.getEntriesRemaining() <= EXPIRING_WITH_ENTRIES;
		return endsSoon || fewEntries;
	}

}
