package studioforyou.pl.backend.membership;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Cancellation and dispute accounting in the booking owner's transaction; reads membership-owned rows only. */
public interface PersonalTrainingAccounting {

	Quote preview(UUID customerId, UUID passId, UUID creditId, UUID reservationId, boolean inTime, Instant now);

	void cancel(UUID customerId, UUID passId, UUID creditId, UUID reservationId, boolean inTime,
			Quote expected, UUID actorId, Instant now);

	/**
	 * What each decision on a client's dispute about a held session would do to the session's
	 * funding. Booking supplies the reservation's pass or make-up credit; both {@code null} when the
	 * session was not funded in the application.
	 */
	DisputeTerms disputeTerms(UUID customerId, UUID passId, UUID creditId, UUID reservationId, Instant now);

	/**
	 * Applies an administrator's decision on a disputed session to its funding, after the caller has
	 * locked the event and the reservation in the same transaction. The session's entry is settled as
	 * used in every case, so the session keeps counting as held; an upheld dispute then gives the entry
	 * or the make-up credit back, and one upheld with a make-up grants a new credit instead.
	 *
	 * @param makeupValidityDays how long a credit granted by {@link DisputeResolution#UPHELD_MAKEUP} is valid
	 * @param reason the administrator's explanation, kept with a returned entry
	 * @return what the decision did
	 */
	DisputeEffect settleDispute(UUID customerId, UUID passId, UUID creditId, UUID reservationId,
			DisputeResolution resolution, Integer makeupValidityDays, String reason, UUID actorId, Instant now);

	enum Effect { MAKEUP_GRANTED, MAKEUP_RETURNED, ENTRY_FORFEITED, NO_ENTRY }

	record Quote(Effect effect, Integer validityDays, String reason, String passName) {}

	/** The administrator's decision on a client's dispute about a personal session. */
	enum DisputeResolution {

		/** The client is right: the session's entry or make-up credit is given back. */
		UPHELD,
		/** The client is right, and gets a new make-up credit instead; the entry stays used. */
		UPHELD_MAKEUP,
		/** The session was held as recorded: the entry stays used. */
		REJECTED

	}

	/** What a decision on a dispute did, or would do, to the session's funding. */
	enum DisputeEffect { ENTRY_RETURNED, MAKEUP_RETURNED, MAKEUP_GRANTED, NO_CHANGE }

	/** How the disputed session was paid for in the application. */
	enum DisputeFunding { PASS, UNLIMITED_PASS, MAKEUP, NONE }

	/**
	 * @param passName the funding pass, or the pass the make-up credit came from
	 * @param entriesRemaining the funding pass's balance; {@code null} for unlimited entries or other funding
	 * @param passValidUntil the funding pass's last day; {@code null} for other funding
	 * @param makeupExpiresAt when the make-up credit that funded the session expires; {@code null} for other funding
	 * @param makeupValidityDays the suggested validity of a granted credit: the product's make-up policy's,
	 * otherwise {@link #DEFAULT_MAKEUP_VALIDITY_DAYS}; {@code null} when no credit can be granted
	 */
	record DisputeTerms(DisputeFunding funding, String passName, Short entriesRemaining, LocalDate passValidUntil,
			Instant makeupExpiresAt, DisputeOption upheld, DisputeOption upheldMakeup, Integer makeupValidityDays) {

		public static final int DEFAULT_MAKEUP_VALIDITY_DAYS = 30;

		public DisputeOption option(DisputeResolution resolution) {
			return switch (resolution) {
				case UPHELD -> upheld;
				case UPHELD_MAKEUP -> upheldMakeup;
				case REJECTED -> new DisputeOption(true, DisputeEffect.NO_CHANGE, null);
			};
		}
	}

	/**
	 * @param notice why the decision is not available, or what the administrator should know before
	 * taking it: {@code PASS_EXPIRED}, {@code PASS_CANCELLED}, {@code NO_ALLOCATED_ENTRY},
	 * {@code UNLIMITED_PASS}, {@code NOT_FUNDED}, {@code MAKEUP_EXPIRED} or {@code MAKEUP_NOT_USABLE}
	 */
	record DisputeOption(boolean allowed, DisputeEffect effect, String notice) {}
}
