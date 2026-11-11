package studioforyou.pl.backend.membership;

import java.time.Instant;
import java.util.UUID;

/** Cancellation accounting in the booking owner's transaction; reads membership-owned rows only. */
public interface PersonalTrainingAccounting {

	Quote preview(UUID customerId, UUID passId, UUID creditId, UUID reservationId, boolean inTime, Instant now);

	void cancel(UUID customerId, UUID passId, UUID creditId, UUID reservationId, boolean inTime,
			Quote expected, UUID actorId, Instant now);

	enum Effect { MAKEUP_GRANTED, MAKEUP_RETURNED, ENTRY_FORFEITED, NO_ENTRY }

	record Quote(Effect effect, Integer validityDays, String reason, String passName) {}
}
