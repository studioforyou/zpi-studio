package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.CustomerPassStatus;
import studioforyou.pl.backend.membership.PassPurchases;
import studioforyou.pl.backend.offering.PassVariantLookup;
import studioforyou.pl.backend.offering.PassVariantTerms;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * Passes bought with an order. The pass copies the variant's terms when the order is placed
 * and waits for the payment; the activation entry of its ledger is written when it is paid.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class PassPurchaseService implements PassPurchases {

	private final CustomerPassRepository passes;

	private final PassLedgerRepository ledger;

	private final CustomerProfiles customers;

	private final PassVariantLookup variants;

	private final StudioSettings studio;

	PassPurchaseService(CustomerPassRepository passes, PassLedgerRepository ledger, CustomerProfiles customers,
			PassVariantLookup variants, StudioSettings studio) {
		this.passes = passes;
		this.ledger = ledger;
		this.customers = customers;
		this.variants = variants;
		this.studio = studio;
	}

	@Override
	public PurchasedPass createPending(PendingPurchase purchase) {
		ProfileDetails customer = customers.findById(purchase.customerId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
		if (!customer.active()) {
			throw new ApiException(ErrorCode.CUSTOMER_NOT_ACTIVE, "The participant's profile is not active.");
		}
		PassVariantTerms terms = variants.findTerms(purchase.variantId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass product variant not found."));
		CustomerPassService.requireOnSale(terms);
		CustomerPassService.requireAudience(terms.audience(), customer.kind());

		LocalDate validFrom = firstDay(purchase);
		LocalDate validUntil = validFrom.plusDays(terms.validityDays() - 1L);
		CustomerPass pass = passes.save(CustomerPass.pendingPayment(purchase.customerId(), terms.variantId(),
				purchase.orderItemId(), terms.productName(), terms.entriesCount(), terms.price(), validFrom, validUntil,
				purchase.actorAccountId()));
		passes.flush();
		return result(pass);
	}

	@Override
	public PurchasedPass activate(UUID orderItemId, UUID actorAccountId) {
		CustomerPass pass = passes.findByOrderItemIdForUpdate(orderItemId)
			.orElseThrow(() -> new IllegalStateException("No pass was bought with order item " + orderItemId + "."));
		pass.activatePaid(studio.today());
		ledger.save(PassLedgerEntry.activation(pass, null, actorAccountId));
		passes.flush();
		return result(pass);
	}

	/**
	 * The chosen first day, or the day after the followed pass ends. A pass that has already
	 * ended is followed from today.
	 */
	private LocalDate firstDay(PendingPurchase purchase) {
		LocalDate today = studio.today();
		if (purchase.startAfterPassId() == null) {
			if (purchase.startsOn().isBefore(today)) {
				throw ApiException.invalidField("startsOn", FieldErrorCode.OUT_OF_RANGE,
						"The pass cannot start before today.");
			}
			return purchase.startsOn();
		}
		CustomerPass followed = passes.findByIdAndCustomerId(purchase.startAfterPassId(), purchase.customerId())
			.orElseThrow(() -> ApiException.invalidField("startAfterPassId", FieldErrorCode.INVALID_VALUE,
					"The participant has no such pass."));
		CustomerPassService.requireStatus(followed, CustomerPassStatus.ACTIVE, CustomerPassStatus.SCHEDULED,
				CustomerPassStatus.EXPIRED);
		LocalDate dayAfter = followed.getValidUntil().plusDays(1);
		return dayAfter.isAfter(today) ? dayAfter : today;
	}

	private static PurchasedPass result(CustomerPass pass) {
		return new PurchasedPass(pass.getId(), pass.getStatus(), pass.getValidFrom(), pass.getValidUntil());
	}

}
