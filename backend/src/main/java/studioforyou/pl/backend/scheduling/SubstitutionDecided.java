package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that decided a substitution request, so that the trainers it
 * concerns hear about the decision after commit. A request withdrawn by its candidate or closed
 * because its event was cancelled publishes nothing: the trainers already know or get the
 * cancellation itself.
 *
 * @param version the request's version after the decision
 * @param originalStaffId the trainer who asked to be replaced
 * @param substituteStaffId the trainer who takes the event, or whose claim was turned down; null
 * when the studio closed a request nobody had claimed
 * @param substituteName that trainer's name
 * @param selfClaimed whether the substitute took the event themselves, without the studio's approval
 * @param note the administrator's note on the decision
 */
public record SubstitutionDecided(UUID requestId, int version, UUID eventId, Outcome outcome, UUID originalStaffId,
		@Nullable UUID substituteStaffId, @Nullable String substituteName, boolean selfClaimed, String title,
		String rooms, Instant startsAt, Instant endsAt, @Nullable String note) {

	/** What the decision means for the trainers. */
	public enum Outcome {

		/** The substitute now leads the event instead of the original trainer. */
		COVERED,

		/** The studio turned down the substitute's claim; the request is open again for others. */
		CLAIM_REJECTED,

		/** The studio closed the request; the original trainer keeps the event. */
		CLOSED

	}

}
