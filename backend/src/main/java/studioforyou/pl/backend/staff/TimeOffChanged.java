package studioforyou.pl.backend.staff;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published after the studio decides on a staff member's time off, records it or cancels it, so
 * the staff member can be told. A staff member's own changes (a new request, an edit, a
 * withdrawal) publish nothing.
 *
 * @param timeOffId the time off
 * @param staffAccountId the account of the staff member it belongs to
 * @param change what the studio did
 * @param kind {@code VACATION}, {@code SICK_LEAVE} or {@code UNAVAILABLE}
 * @param startsAt start, inclusive
 * @param endsAt end, exclusive
 * @param note the administrator's note on the decision, if any
 */
public record TimeOffChanged(UUID timeOffId, UUID staffAccountId, Change change, String kind, Instant startsAt,
		Instant endsAt, @Nullable String note) {

	/** What the studio did with the time off. */
	public enum Change {

		/** A request was approved, or the studio recorded time off that is approved at once. */
		APPROVED,

		/** A request was rejected. */
		REJECTED,

		/** The studio cancelled requested or approved time off. */
		CANCELLED

	}

}
