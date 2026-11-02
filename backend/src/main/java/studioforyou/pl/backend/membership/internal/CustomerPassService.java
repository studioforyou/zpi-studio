package studioforyou.pl.backend.membership.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * The staff can also extend a pass and suspend it when its product allows it; each change of
 * the last valid day is an {@code EXTENSION} entry of the ledger.
 */
@Service
@Transactional
public class CustomerPassService {

	private final CustomerPassRepository passes;

	private final PassLedgerRepository ledger;

	private final CustomerProfiles customers;

	private final PassVariantLookup variants;

	private final PassSuspensionRepository suspensions;

	private final StudioSettings studio;

	public CustomerPassService(CustomerPassRepository passes, PassLedgerRepository ledger,
			PassSuspensionRepository suspensions, CustomerProfiles customers, PassVariantLookup variants,
			StudioSettings studio) {
		this.passes = passes;
		this.ledger = ledger;
		this.suspensions = suspensions;
		this.customers = customers;
		this.variants = variants;
		this.studio = studio;
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

	/**
	 * Suspensions of the given passes that were not cancelled, oldest first, by pass ID.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, List<PassSuspension>> suspensionsOf(Collection<CustomerPass> heldPasses) {
		Set<UUID> ids = heldPasses.stream().map(CustomerPass::getId).collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		return suspensions.findByPassIdInAndCancelledAtIsNullOrderByStartsOn(ids)
			.stream()
			.collect(Collectors.groupingBy(PassSuspension::getPassId));
	}

	/**
	 * Moves the last valid day of a pass to a later date, as an exception agreed with the
	 * participant. Any product allows it. An expired pass becomes usable again.
	 */
	public CustomerPass extend(UUID customerId, UUID passId, Extension command, UUID actorAccountId) {
		CustomerPass pass = lockPass(customerId, passId, command.version());
		requireStatus(pass, CustomerPassStatus.ACTIVE, CustomerPassStatus.SCHEDULED, CustomerPassStatus.EXPIRED);
		LocalDate before = pass.getValidUntil();
		LocalDate today = studio.today();
		if (!command.validUntil().isAfter(before)) {
			throw ApiException.invalidField("validUntil", FieldErrorCode.OUT_OF_RANGE,
					"The new last day must be later than the current one, " + before + ".");
		}
		if (command.validUntil().isBefore(today)) {
			throw ApiException.invalidField("validUntil", FieldErrorCode.OUT_OF_RANGE,
					"The pass would still be expired; the new last day must be today or later.");
		}
		pass.changeValidUntil(command.validUntil(), today);
		ledger.save(PassLedgerEntry.extension(passId, before, command.validUntil(), blankToNull(command.reason()),
				actorAccountId));
		passes.flush();
		return pass;
	}

	/**
	 * Suspends a pass for a period from today on, within its validity, when its product allows
	 * suspension. The period may not overlap another suspension, and all suspensions of the pass
	 * together may not exceed the product's limit. The last valid day moves by the length of the
	 * period. Reservations already made with the pass are not changed.
	 */
	public CustomerPass suspend(UUID customerId, UUID passId, Suspension command, UUID actorAccountId) {
		CustomerPass pass = lockPass(customerId, passId, command.version());
		requireStatus(pass, CustomerPassStatus.ACTIVE, CustomerPassStatus.SCHEDULED);
		PassVariantTerms terms = variants.findTerms(pass.getVariantId())
			.orElseThrow(() -> new IllegalStateException("The variant of pass " + passId + " is missing."));
		if (!terms.suspensionAllowed()) {
			throw new ApiException(ErrorCode.PASS_SUSPENSION_NOT_ALLOWED, "The pass product does not allow suspension.");
		}
		LocalDate startsOn = command.startsOn();
		LocalDate endsOn = command.endsOn();
		LocalDate today = studio.today();
		if (endsOn.isBefore(startsOn)) {
			throw ApiException.invalidField("endsOn", FieldErrorCode.OUT_OF_RANGE,
					"The suspension cannot end before it starts.");
		}
		if (startsOn.isBefore(today) || startsOn.isBefore(pass.getValidFrom())) {
			throw ApiException.invalidField("startsOn", FieldErrorCode.OUT_OF_RANGE,
					"The suspension must start today or later, within the pass's validity.");
		}
		if (endsOn.isAfter(pass.getValidUntil())) {
			throw ApiException.invalidField("endsOn", FieldErrorCode.OUT_OF_RANGE,
					"The suspension must end by the pass's last day, " + pass.getValidUntil() + ".");
		}
		if (suspensions.overlaps(passId, startsOn, endsOn)) {
			throw new ApiException(ErrorCode.PASS_SUSPENSION_NOT_ALLOWED,
					"The period overlaps another suspension of the pass.");
		}
		long days = PassSuspension.days(startsOn, endsOn);
		if (terms.maxSuspensionDays() != null) {
			long used = suspensions.findByPassIdInAndCancelledAtIsNullOrderByStartsOn(Set.of(passId))
				.stream()
				.mapToLong(PassSuspension::days)
				.sum();
			if (used + days > terms.maxSuspensionDays()) {
				throw new ApiException(ErrorCode.PASS_SUSPENSION_NOT_ALLOWED,
						"The pass can be suspended for at most " + terms.maxSuspensionDays() + " days in total; "
								+ used + " are already used.",
						Map.of("maxSuspensionDays", terms.maxSuspensionDays(), "usedSuspensionDays", used));
			}
		}
		String reason = blankToNull(command.reason());
		suspensions.save(new PassSuspension(passId, startsOn, endsOn, reason, actorAccountId));
		LocalDate before = pass.getValidUntil();
		pass.changeValidUntil(before.plusDays(days), today);
		ledger.save(PassLedgerEntry.extension(passId, before, pass.getValidUntil(), reason, actorAccountId));
		passes.flush();
		return pass;
	}

	/**
	 * Cancels the days of a suspension from today on: one that has not started yet is cancelled
	 * as a whole, one in progress ends yesterday. The last valid day moves back by the days taken
	 * off. A suspension that has already ended cannot be changed.
	 */
	public CustomerPass cancelSuspension(UUID customerId, UUID passId, UUID suspensionId, long version,
			UUID actorAccountId) {
		CustomerPass pass = lockPass(customerId, passId, version);
		requireStatus(pass, CustomerPassStatus.ACTIVE, CustomerPassStatus.SCHEDULED);
		PassSuspension suspension = suspensions.findByIdAndPassId(suspensionId, passId)
			.filter((found) -> found.getCancelledAt() == null)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Suspension not found."));
		LocalDate today = studio.today();
		if (suspension.getEndsOn().isBefore(today)) {
			throw new ApiException(ErrorCode.PASS_SUSPENSION_NOT_ALLOWED,
					"The suspension has already ended and cannot be cancelled.");
		}
		long removed = suspension.cancelFrom(today);
		LocalDate before = pass.getValidUntil();
		pass.changeValidUntil(before.minusDays(removed), today);
		ledger.save(PassLedgerEntry.extension(passId, before, pass.getValidUntil(), "Suspension cancelled",
				actorAccountId));
		passes.flush();
		return pass;
	}

	private CustomerPass lockPass(UUID customerId, UUID passId, long version) {
		CustomerPass pass = passes.findForUpdate(passId, customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass not found."));
		if (pass.getVersion() != version) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT,
					"The pass was modified by another request. Reload it and try again.");
		}
		return pass;
	}

	private static void requireStatus(CustomerPass pass, CustomerPassStatus... allowed) {
		if (!List.of(allowed).contains(pass.getStatus()) || pass.getValidUntil() == null) {
			throw new ApiException(ErrorCode.PASS_NOT_USABLE,
					"The operation is not available for a pass with status " + pass.getStatus() + ".");
		}
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

	/**
	 * @param validUntil the new last valid day, later than the current one
	 * @param reason an optional note for the pass history
	 * @param version the pass version the change is based on
	 */
	public record Extension(LocalDate validUntil, String reason, long version) {
	}

	/**
	 * @param startsOn the first suspended day
	 * @param endsOn the last suspended day, inclusive
	 * @param reason an optional note, kept with the suspension and in the pass history
	 * @param version the pass version the change is based on
	 */
	public record Suspension(LocalDate startsOn, LocalDate endsOn, String reason, long version) {
	}

}
