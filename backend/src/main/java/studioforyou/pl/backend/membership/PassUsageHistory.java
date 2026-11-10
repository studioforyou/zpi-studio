package studioforyou.pl.backend.membership;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import studioforyou.pl.backend.shared.api.PageResponse;

/** Entry accounting of a caller-authorized participant's pass; no booking tables are read. */
public interface PassUsageHistory {

	/** Headers of passes held by a caller-authorized participant set, including expired passes. */
	List<Pass> passes(Collection<UUID> profileIds);

	/** A globally ordered page for caller-authorized passes; date bounds concern accounting changes. */
	PageResponse<Entry> list(Collection<UUID> passIds, Collection<UUID> excludedReservations,
			Instant from, Instant to, int page, int size);

	record Pass(UUID id, UUID profileId, UUID variantId, String name) {
	}

	/** One reservation's net accounting, or an individual balance correction. */
	record Entry(UUID id, UUID passId, UUID reservationId, String kind, Integer entriesChange, Instant occurredAt) {
	}

}
