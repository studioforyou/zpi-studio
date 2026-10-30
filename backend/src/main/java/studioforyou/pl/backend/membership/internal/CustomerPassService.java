package studioforyou.pl.backend.membership.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.CustomerPassStatus;
import studioforyou.pl.backend.offering.PassProductAudience;
import studioforyou.pl.backend.offering.PassVariantLookup;
import studioforyou.pl.backend.offering.PassVariantTerms;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * Passes held by participants. The studio staff assign a pass directly, outside of an order:
 * the pass copies the variant's terms and its activation is the first entry of its ledger.
 */
@Service
@Transactional
public class CustomerPassService {

	private final CustomerPassRepository passes;

	private final PassLedgerRepository ledger;

	private final CustomerProfiles customers;

	private final PassVariantLookup variants;

	private final StudioSettings studio;

	private final CustomerPassLock passLock;

	public CustomerPassService(CustomerPassRepository passes, PassLedgerRepository ledger,
			CustomerProfiles customers, PassVariantLookup variants, StudioSettings studio, CustomerPassLock passLock) {
		this.passes = passes;
		this.ledger = ledger;
		this.customers = customers;
		this.variants = variants;
		this.studio = studio;
		this.passLock = passLock;
	}

	/**
	 * The participant's passes, newest first.
	 */
	@Transactional(readOnly = true)
	public List<CustomerPass> list(UUID customerId) {
		requireCustomer(customerId);
		return passes.findByCustomerIdOrderByCreatedAtDesc(customerId);
	}

	@Transactional(readOnly = true)
	public CustomerPass get(UUID customerId, UUID passId) {
		return passes.findByIdAndCustomerId(passId, customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass not found."));
	}

	/**
	 * Passes of a profile the account acts for, newest first, without cancelled ones. A profile
	 * the account may not act for is reported as not found, so its existence is not revealed.
	 */
	@Transactional(readOnly = true)
	public List<CustomerPass> listOwn(UUID accountId, UUID profileId) {
		if (!customers.isActingFor(accountId, profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		return passes.findByCustomerIdAndStatusNotOrderByCreatedAtDesc(profileId, CustomerPassStatus.CANCELLED);
	}

	/**
	 * Assigns a pass with the variant's terms to an active participant. It is valid for the
	 * variant's number of days, counting the start date, and is scheduled when it starts later
	 * than today in the studio's time zone.
	 */
	public CustomerPass assign(UUID customerId, Assignment command, UUID actorAccountId) {
		ProfileDetails customer = requireCustomer(customerId);
		if (!customer.active()) {
			throw new ApiException(ErrorCode.CUSTOMER_NOT_ACTIVE, "The participant's profile is not active.");
		}
		PassVariantTerms terms = variants.findTerms(command.variantId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass product variant not found."));
		requireOnSale(terms);
		requireAudience(terms.audience(), customer.kind());

		LocalDate validUntil = command.validFrom().plusDays(terms.validityDays() - 1L);
		LocalDate today = studio.today();
		if (validUntil.isBefore(today)) {
			throw ApiException.invalidField("validFrom", FieldErrorCode.OUT_OF_RANGE,
					"A pass starting on this date would already be expired.");
		}
		BigDecimal pricePaid = terms.price();
		if (command.pricePaid() != null) {
			if (!terms.currency().equals(command.currency())) {
				throw ApiException.invalidField("pricePaid.currency", FieldErrorCode.INVALID_VALUE,
						"The price must be in the variant's currency, " + terms.currency() + ".");
			}
			pricePaid = command.pricePaid();
		}
		CustomerPassStatus status = command.validFrom().isAfter(today) ? CustomerPassStatus.SCHEDULED
				: CustomerPassStatus.ACTIVE;

		CustomerPass pass = passes.save(new CustomerPass(customerId, terms.variantId(), terms.productName(),
				terms.entriesCount(), pricePaid, status, command.validFrom(), validUntil, actorAccountId));
		ledger.save(PassLedgerEntry.activation(pass, blankToNull(command.reason()), actorAccountId));
		passes.flush();
		return pass;
	}

	/** Changes the available balance and appends its history atomically with booking accounting. */
	public CustomerPass correctEntries(UUID customerId, UUID passId, EntryCorrection command, UUID actorAccountId) {
		CustomerPass pass = passLock.acquire(customerId, passId);
		if (pass.getVersion() != command.version()) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT,
					"The pass was modified by another request. Reload it and try again.");
		}
		if (pass.getEntriesRemaining() == null) {
			throw ApiException.invalidField("entriesDelta", FieldErrorCode.INVALID_VALUE,
					"Unlimited passes have no entry balance to correct.");
		}
		if (command.entriesDelta() == 0) {
			throw ApiException.invalidField("entriesDelta", FieldErrorCode.INVALID_VALUE,
					"An entry correction must change the balance.");
		}
		long balance = (long) pass.getEntriesRemaining() + command.entriesDelta();
		if (balance < 0 || balance > Short.MAX_VALUE || balance + ledger.pendingReturns(passId) > Short.MAX_VALUE) {
			throw ApiException.invalidField("entriesDelta", FieldErrorCode.OUT_OF_RANGE,
					"The balance must stay between 0 and 32767, including entries that may still be returned.");
		}
		pass.correctEntries((short) balance);
		ledger.save(PassLedgerEntry.correction(passId, (short) command.entriesDelta(), blankToNull(command.reason()),
				actorAccountId));
		passes.flush();
		return pass;
	}

	private ProfileDetails requireCustomer(UUID customerId) {
		return customers.findById(customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
	}

	private static void requireOnSale(PassVariantTerms terms) {
		if (!terms.productActive()) {
			throw new ApiException(ErrorCode.PASS_PRODUCT_ARCHIVED, "The pass product is archived.");
		}
		if (!terms.variantActive()) {
			throw new ApiException(ErrorCode.PASS_VARIANT_INACTIVE, "The pass product variant is not on sale.");
		}
	}

	private static void requireAudience(PassProductAudience audience, CustomerKind kind) {
		boolean allowed = switch (audience) {
			case ANY -> true;
			case ADULT -> kind == CustomerKind.ADULT;
			case CHILD -> kind == CustomerKind.CHILD;
		};
		if (!allowed) {
			throw new ApiException(ErrorCode.PASS_AUDIENCE_MISMATCH,
					"The pass product is meant for " + audience + " participants, the participant is " + kind + ".");
		}
	}

	private static String blankToNull(String text) {
		return (text == null || text.isBlank()) ? null : text.strip();
	}

	/**
	 * @param validFrom the first day the pass is valid
	 * @param pricePaid the price actually paid, or {@code null} for the variant's price
	 * @param currency the currency of {@code pricePaid}; ignored without it
	 * @param reason an optional note for the pass history, e.g. "Gift from the studio"
	 */
	public record Assignment(UUID variantId, LocalDate validFrom, BigDecimal pricePaid, String currency,
			String reason) {
	}

	public record EntryCorrection(int entriesDelta, String reason, int version) {
	}

}
