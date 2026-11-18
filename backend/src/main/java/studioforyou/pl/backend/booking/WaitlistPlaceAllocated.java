package studioforyou.pl.backend.booking;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** A committed queue allocation is either an offer to confirm or an automatic reservation. */
public record WaitlistPlaceAllocated(UUID entryId, UUID customerId, UUID eventId,
		@Nullable UUID reservationId, @Nullable Instant offerExpiresAt) {

	public WaitlistPlaceAllocated {
		if ((reservationId == null) == (offerExpiresAt == null)) {
			throw new IllegalArgumentException("A waiting-list allocation must be an offer or a reservation.");
		}
	}

}
