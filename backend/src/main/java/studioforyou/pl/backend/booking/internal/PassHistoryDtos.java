package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.membership.PassChangeHistory.Kind;
import studioforyou.pl.backend.membership.PassChangeHistory.MakeupCreditState;
import studioforyou.pl.backend.shared.api.PageResponse;

/** Response bodies of the administrators' pass change history (#85). */
final class PassHistoryDtos {

	private PassHistoryDtos() {
	}

	/** The filter of the history: which kinds of changes it shows. */
	enum PassHistoryCategory {

		/** Entries booked, used and returned for classes and sessions. */
		ENTRIES,
		/** Balance corrections by the staff and refunds after upheld disputes. */
		CORRECTIONS,
		/** Extensions, suspensions and their cancellations, and the expiry. */
		VALIDITY,
		/** Make-up credits granted from the pass. */
		MAKEUP,
		/** The start of the pass and the decision not to renew it. */
		PASS;

		static PassHistoryCategory of(Kind kind) {
			return switch (kind) {
				case ENTRY_RESERVED, ENTRY_USED, ENTRY_RETURNED -> ENTRIES;
				case CORRECTED, DISPUTE_REFUND -> CORRECTIONS;
				case EXTENDED, SUSPENDED, SUSPENSION_CANCELLED, EXPIRED -> VALIDITY;
				case MAKEUP_GRANTED -> MAKEUP;
				case ACTIVATED, RENEWAL_DECLINED -> PASS;
			};
		}

	}

	/** Who the author of a change is to the participant. */
	enum ActorRole {

		/** The participant themselves. */
		CLIENT,
		/** A guardian acting for a child. */
		GUARDIAN,
		/** The studio's staff: an administrator or a trainer. */
		STAFF

	}

	@Schema(name = "PassHistory", description = "One page of a pass's changes, newest first.")
	record PassHistoryResponse(PassSummary pass,
			@Schema(description = "How many changes of each category the whole history has, also without the filter.")
			Map<PassHistoryCategory, Long> counts,
			PageResponse<PassHistoryEntry> entries) {
	}

	@Schema(name = "PassHistoryPass")
	record PassSummary(UUID id, UUID customerId, String productName,
			@Schema(description = "null for unlimited entries.") @Nullable Short entriesTotal,
			@Schema(description = "The balance now; null for unlimited entries.") @Nullable Short entriesRemaining,
			@Schema(description = "What the history's changes add up to; it differs from entriesRemaining only when "
					+ "the history misses changes, e.g. from before it was kept.") @Nullable Integer ledgerBalance,
			@Schema(description = "Bought with an order in the application rather than assigned by the staff.")
			boolean purchased) {
	}

	@Schema(name = "PassHistoryEntry", description = "One change of the pass.")
	record PassHistoryEntry(UUID id,
			@Schema(description = "ACTIVATED, ENTRY_RESERVED (a booking took an entry), ENTRY_USED (the booked entry "
					+ "was settled as used), ENTRY_RETURNED, CORRECTED (by the staff), DISPUTE_REFUND (an upheld "
					+ "dispute gave the entry back), EXTENDED, SUSPENDED, SUSPENSION_CANCELLED, EXPIRED, MAKEUP_GRANTED "
					+ "or RENEWAL_DECLINED.") Kind kind,
			PassHistoryCategory category, Instant occurredAt,
			@Schema(description = "Who made the change; null for the system or when it is not recorded (a declined "
					+ "renewal is always the participant's or a guardian's).") @Nullable Actor actor,
			@Schema(description = "The note given with the change; internal, never shown to the participant.")
			@Nullable String reason,
			@Schema(description = "The signed change of the balance; null for unlimited passes and changes that do "
					+ "not touch the balance. 0 when a booked entry is settled as used.") @Nullable Integer entriesChange,
			@Schema(description = "The balance after the change, as the history adds it up; null when entriesChange "
					+ "is null.") @Nullable Integer balanceAfter,
			@Schema(description = "The last valid day before an activation, extension or suspension.")
			@Nullable LocalDate validUntilBefore,
			@Nullable LocalDate validUntilAfter,
			@Schema(description = "The suspended days, or for SUSPENSION_CANCELLED the days given back.")
			@Nullable Period suspension,
			@Schema(description = "The class or session the entry was booked for, refunded for or, for a make-up "
					+ "credit, granted for.") @Nullable Session session,
			@Nullable MakeupCredit makeupCredit) {
	}

	@Schema(name = "PassHistoryActor")
	record Actor(@Schema(description = "null when the account has no name.", example = "Beata Lis") @Nullable String name,
			ActorRole role) {
	}

	@Schema(name = "PassHistoryPeriod", description = "Days of a suspension, both inclusive.")
	record Period(LocalDate startsOn, LocalDate endsOn) {
	}

	@Schema(name = "PassHistorySession", description = "A reservation of the pass's holder, with its event.")
	record Session(UUID reservationId, @Nullable UUID eventId, @Nullable String title, @Nullable String serviceTypeName,
			@Nullable Instant startsAt, @Nullable Instant endsAt,
			@Schema(description = "The studio cancelled the event.") boolean eventCancelled,
			@Schema(description = "SELF (the participant or a guardian in the app), STAFF or WAITLIST.") String source,
			@Nullable Actor bookedBy,
			@Schema(description = "ACTIVE or CANCELLED.") String status,
			@Schema(description = "IN_TIME, LATE or STUDIO; null while the place is not cancelled.") @Nullable String cancelKind,
			@Nullable Instant cancelledAt, @Nullable Actor cancelledBy,
			@Schema(description = "UNMARKED, PRESENT or ABSENT.") String attendance) {
	}

	@Schema(name = "PassHistoryMakeupCredit", description = "A make-up credit granted from the pass, as it is now.")
	record MakeupCredit(UUID id, Instant expiresAt, MakeupCreditState state, @Nullable Instant usedAt,
			@Schema(description = "The booking the credit paid for, while it is used.") @Nullable Session usedFor) {
	}

}
