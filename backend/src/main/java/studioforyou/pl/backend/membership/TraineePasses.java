package studioforyou.pl.backend.membership;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only pass and reservation-funding facts for an already-authorized trainer view. */
public interface TraineePasses {

	Map<UUID, List<Pass>> find(Collection<UUID> customerIds, Set<UUID> serviceTypeIds,
			LocalDate checkedOn, Set<LocalDate> referenceDays);

	Map<UUID, Funding> funding(Collection<FundingRequest> reservations);

	/** Null balance means unlimited; eligible days apply existing booking rules without allocating an entry. */
	record Pass(UUID id, String productName, CustomerPassStatus status, Short entriesRemaining,
			LocalDate validFrom, LocalDate validUntil, Set<UUID> serviceTypeIds, boolean eligible,
			boolean lowBalance, Set<LocalDate> eligibleDays) {
	}

	record FundingRequest(UUID reservationId, UUID customerId, UUID passId, UUID makeupCreditId) {
	}

	/** NO_PASS is outside pass accounting, not proof that the participant has not paid. */
	record Funding(String state, UUID passId, String passName) {
	}

}
