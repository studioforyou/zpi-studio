package studioforyou.pl.backend.membership;

import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published when a booking takes the last entry of a pass, so its balance drops to 0. Passes
 * without an entry limit never publish it. Returning the entry (e.g. a cancelled booking) and
 * taking it again publishes it again; listeners must not act twice for one pass.
 *
 * @param passId the pass
 * @param customerId the participant holding the pass
 * @param productName the pass name the participant knows
 * @param validUntil the last day of the pass
 */
public record PassLastEntryUsed(UUID passId, UUID customerId, String productName, @Nullable LocalDate validUntil) {
}
