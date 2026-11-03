package studioforyou.pl.backend.membership;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Passes bought with an order. The payments module creates the pass when the order is placed,
 * waiting for the payment, and activates it in the transaction that confirms the payment. The
 * caller owns the order and the participant authorization.
 */
public interface PassPurchases {

	/**
	 * Creates a pass of the variant for the order line, waiting for its payment. The pass starts
	 * on {@code startsOn}, or the day after the last day of {@code startAfterPassId}; exactly one
	 * of them is given. The variant must be on sale and meant for the participant.
	 */
	PurchasedPass createPending(PendingPurchase purchase);

	/**
	 * Activates the pass bought with the order line once the order is paid: scheduled when it
	 * starts after today, active otherwise. Joins the caller's transaction.
	 */
	PurchasedPass activate(UUID orderItemId, UUID actorAccountId);

	/**
	 * @param customerId the participant the pass is for
	 * @param variantId the variant being bought
	 * @param startsOn the chosen first day, or {@code null} with {@code startAfterPassId}
	 * @param startAfterPassId a pass of the participant the new one follows, or {@code null}
	 * with {@code startsOn}
	 * @param orderItemId the order line paying for the pass
	 * @param actorAccountId the account placing the order
	 */
	record PendingPurchase(UUID customerId, UUID variantId, LocalDate startsOn, UUID startAfterPassId,
			UUID orderItemId, UUID actorAccountId) {
	}

	/**
	 * @param validUntil the last day the pass is valid, inclusive
	 */
	record PurchasedPass(UUID passId, CustomerPassStatus status, LocalDate validFrom, LocalDate validUntil) {
	}

}
