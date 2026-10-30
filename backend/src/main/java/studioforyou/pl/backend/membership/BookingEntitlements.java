package studioforyou.pl.backend.membership;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Pass eligibility and entry accounting for a supplied occurrence. The caller supplies the
 * occurrence date in the studio's time zone and owns participant authorization.
 */
public interface BookingEntitlements {

	List<EligiblePass> eligible(UUID customerId, UUID serviceTypeId, LocalDate occurrenceDate);

	/**
	 * Allocates one entry, or records unlimited use. The caller must insert and flush the
	 * reservation first, and hold its occurrence/reservation locks in the same transaction.
	 * Repeating the allocation for the same pass and reservation does not spend twice.
	 */
	void reserve(UUID customerId, UUID passId, UUID serviceTypeId, LocalDate occurrenceDate, UUID reservationId,
			UUID actorAccountId);

	/**
	 * Settles an allocation once: returns its entry when permitted, otherwise records that
	 * the reserved entry is consumed. The caller holds the occurrence/reservation locks.
	 */
	void release(UUID customerId, UUID passId, UUID reservationId, boolean returnEntry, UUID actorAccountId);

	/** A usable pass; a null balance denotes unlimited entries. */
	record EligiblePass(UUID id, String productName, Short entriesRemaining, LocalDate validFrom,
			LocalDate validUntil) {
	}

}
