package studioforyou.pl.backend.membership;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Credit accounting in the coordinator's transaction. No reservation tables are read here. */
public interface MakeupEntitlements {

	Credit available(UUID profileId, UUID creditId);

	void reserve(UUID profileId, UUID creditId, UUID serviceTypeId, Instant startsAt, UUID reservationId);

	boolean canReturn(UUID profileId, UUID creditId, UUID reservationId, Instant now);

	void release(UUID profileId, UUID creditId, UUID reservationId, boolean policyReturns, boolean expectedReturn);

	record Credit(UUID id, String productName, Instant expiresAt, Set<UUID> serviceTypeIds, boolean checkEventDate) {

		public boolean covers(UUID serviceTypeId, Instant startsAt) {
			return serviceTypeIds.contains(serviceTypeId) && (!checkEventDate || startsAt.isBefore(expiresAt));
		}
	}
}
