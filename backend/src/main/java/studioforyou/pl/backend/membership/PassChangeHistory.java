package studioforyou.pl.backend.membership;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Everything that changed one participant's pass, for an already-authorized staff view: its ledger,
 * the make-up credits granted from it and the participant's decision not to renew it. Reservation
 * IDs are returned for the caller to describe; no booking tables are read.
 */
public interface PassChangeHistory {

	/** The participant's pass with its changes, oldest first; empty when the participant holds no such pass. */
	Optional<History> find(UUID customerId, UUID passId);

	/**
	 * @param entriesTotal the pass's entries, {@code null} for unlimited entries
	 * @param entriesRemaining the stored balance, {@code null} for unlimited entries
	 * @param ledgerBalance what the ledger adds up to, {@code null} for unlimited entries; it differs
	 *        from the stored balance only when the ledger misses changes, e.g. from before it was kept
	 * @param purchased whether the pass was bought with an order rather than assigned by the staff
	 */
	record History(UUID passId, UUID customerId, String productName, @Nullable Short entriesTotal,
			@Nullable Short entriesRemaining, @Nullable Integer ledgerBalance, boolean purchased, List<Change> changes) {
	}

	/** What happened to the pass. */
	enum Kind {

		/** The pass started with its entries and validity, bought or assigned by the staff. */
		ACTIVATED,
		/** A booking took an entry, or recorded the use of an unlimited pass. */
		ENTRY_RESERVED,
		/** The booked entry was settled as used: the participant came, did not come or cancelled too late. */
		ENTRY_USED,
		/** The booked entry came back, e.g. after a cancellation in time or by the studio. */
		ENTRY_RETURNED,
		/** The staff changed the balance by hand. */
		CORRECTED,
		/** An entry came back after the studio upheld the participant's dispute about a session. */
		DISPUTE_REFUND,
		/** The last valid day moved, as agreed with the participant. */
		EXTENDED,
		/** A suspension moved the last valid day later. */
		SUSPENDED,
		/** A suspension, or its remaining days, was cancelled and the last valid day moved back. */
		SUSPENSION_CANCELLED,
		/** The pass expired. */
		EXPIRED,
		/** A make-up credit was granted from the pass. */
		MAKEUP_GRANTED,
		/** The participant said they will not renew the pass. */
		RENEWAL_DECLINED

	}

	/**
	 * One change.
	 *
	 * @param id the ledger entry, the make-up credit or, for {@link Kind#RENEWAL_DECLINED}, the pass
	 * @param actorAccountId who made the change, {@code null} for the system or when it is not recorded
	 * @param reason the note given with the change, if any
	 * @param entriesChange the signed change of the balance, {@code null} for unlimited passes and changes
	 *        that never touch the balance
	 * @param balanceAfter the ledger balance after a change of the balance, {@code null} otherwise
	 * @param reservationId the reservation the entry was booked for, refunded for or, for a make-up
	 *        credit, granted for
	 * @param suspension the suspended days, or the days a cancellation gave back
	 */
	record Change(UUID id, Kind kind, Instant occurredAt, @Nullable UUID actorAccountId, @Nullable String reason,
			@Nullable Integer entriesChange, @Nullable Integer balanceAfter, @Nullable LocalDate validUntilBefore,
			@Nullable LocalDate validUntilAfter, @Nullable UUID reservationId, @Nullable Period suspension,
			@Nullable MakeupCredit makeupCredit) {
	}

	/** Days of a suspension, both inclusive. */
	record Period(LocalDate startsOn, LocalDate endsOn) {
	}

	/**
	 * A make-up credit granted from the pass, as it is now.
	 *
	 * @param usedReservationId the booking the credit paid for, while it is used
	 */
	record MakeupCredit(UUID id, Instant expiresAt, MakeupCreditState state, @Nullable Instant usedAt,
			@Nullable UUID usedReservationId) {
	}

	enum MakeupCreditState {

		AVAILABLE, USED,
		/** Past its expiry, or otherwise no longer usable. */
		EXPIRED

	}

}
