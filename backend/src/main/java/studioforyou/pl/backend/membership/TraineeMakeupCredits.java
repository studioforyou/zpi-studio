package studioforyou.pl.backend.membership;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only make-up credits of one participant, for an already-authorized staff view. */
public interface TraineeMakeupCredits {

	/**
	 * Unused credits that have not expired yet, soonest expiry first, and the make-up limits of
	 * the participant's passes: those still running and those the listed credits come from.
	 */
	Summary find(UUID customerId, Instant checkedAt, LocalDate checkedOn);

	/**
	 * @param sourceReservationId the cancelled reservation the credit was granted for, or {@code null}
	 *        when staff granted it directly
	 * @param policyName the make-up policy of the source product now, or {@code null} when it has none
	 * @param serviceTypeIds the services of the source product, which the credit can book
	 */
	record Credit(UUID id, UUID sourcePassId, String productName, Instant grantedAt, Instant expiresAt,
			UUID sourceReservationId, String policyName, Set<UUID> serviceTypeIds) {
	}

	/** How many credits a pass has given out of the most its policy allows. */
	record Limit(UUID passId, String productName, int granted, int max) {

		public boolean reached() {
			return granted >= max;
		}

	}

	record Summary(List<Credit> credits, List<Limit> limits) {
	}

}
