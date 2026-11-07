package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.CustomerPassStatus;
import studioforyou.pl.backend.offering.PassVariantLookup;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/** Pass accounting joins the coordinator's transaction and never reads reservation rows. */
@Service
@Transactional(readOnly = true)
class PassBookingEntitlements implements BookingEntitlements {

	private final CustomerPassRepository passes;

	private final PassLedgerRepository ledger;

	private final PassSuspensionQueries suspensions;

	private final PassVariantLookup variants;

	private final CustomerPassLock passLock;

	PassBookingEntitlements(CustomerPassRepository passes, PassLedgerRepository ledger,
			PassSuspensionQueries suspensions, PassVariantLookup variants, CustomerPassLock passLock) {
		this.passes = passes;
		this.ledger = ledger;
		this.suspensions = suspensions;
		this.variants = variants;
		this.passLock = passLock;
	}

	@Override
	public List<EligiblePass> eligible(UUID customerId, UUID serviceTypeId, LocalDate occurrenceDate) {
		return passes.findByCustomerIdOrderByCreatedAtDesc(customerId)
			.stream()
			.filter(pass -> usableOn(pass, occurrenceDate))
			.filter(pass -> hasEntry(pass))
			.filter(pass -> variants.coversService(pass.getVariantId(), serviceTypeId))
			.map(pass -> new EligiblePass(pass.getId(), pass.getProductName(), pass.getEntriesRemaining(),
					pass.getValidFrom(), pass.getValidUntil()))
			.toList();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(UUID customerId, UUID passId, UUID serviceTypeId, LocalDate occurrenceDate,
			UUID reservationId, UUID actorAccountId) {
		CustomerPass pass = passLock.acquire(customerId, passId);
		if (ledger.existsByPassIdAndReservationIdAndKind(passId, reservationId, PassLedgerKind.ENTRY_RESERVED)) {
			return;
		}
		if (!usableOn(pass, occurrenceDate)) {
			throw new ApiException(ErrorCode.PASS_NOT_USABLE, "The pass is not usable on the class date.");
		}
		if (!variants.coversService(pass.getVariantId(), serviceTypeId)) {
			throw new ApiException(ErrorCode.PASS_SERVICE_MISMATCH, "The pass does not cover this service.");
		}
		if (!hasEntry(pass)) {
			throw new ApiException(ErrorCode.PASS_ENTRIES_EXHAUSTED, "The pass has no entries remaining.");
		}
		short delta = pass.getEntriesRemaining() == null ? 0 : (short) -1;
		pass.reserveEntry();
		ledger.save(PassLedgerEntry.reservation(passId, reservationId, PassLedgerKind.ENTRY_RESERVED, delta,
				actorAccountId));
		passes.flush();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID customerId, UUID passId, UUID reservationId, boolean returnEntry, UUID actorAccountId) {
		CustomerPass pass = passLock.acquire(customerId, passId);
		if (ledger.existsByPassIdAndReservationIdAndKind(passId, reservationId, PassLedgerKind.ENTRY_RETURNED)
				|| ledger.existsByPassIdAndReservationIdAndKind(passId, reservationId, PassLedgerKind.ENTRY_CONSUMED)) {
			return;
		}
		PassLedgerEntry allocation = ledger
			.findByPassIdAndReservationIdAndKind(passId, reservationId, PassLedgerKind.ENTRY_RESERVED)
			.orElseThrow(() -> new ApiException(ErrorCode.PASS_NOT_USABLE,
					"The pass has no entry allocated to this reservation."));
		short delta = returnEntry ? (short) -allocation.getEntriesDelta() : 0;
		pass.returnEntries(delta);
		ledger.save(PassLedgerEntry.reservation(passId, reservationId,
				returnEntry ? PassLedgerKind.ENTRY_RETURNED : PassLedgerKind.ENTRY_CONSUMED, delta, actorAccountId));
		passes.flush();
	}

	private boolean usableOn(CustomerPass pass, LocalDate day) {
		CustomerPassStatus status = pass.getStatus();
		boolean running = status == CustomerPassStatus.ACTIVE || status == CustomerPassStatus.SCHEDULED;
		return running && pass.getValidFrom() != null && pass.getValidUntil() != null
				&& !day.isBefore(pass.getValidFrom()) && !day.isAfter(pass.getValidUntil())
				&& !suspensions.suspendedOn(pass.getId(), day);
	}

	private static boolean hasEntry(CustomerPass pass) {
		return pass.getEntriesRemaining() == null || pass.getEntriesRemaining() > 0;
	}

}
