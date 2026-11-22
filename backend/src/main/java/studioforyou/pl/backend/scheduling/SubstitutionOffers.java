package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Scheduling-owned, current offer data for notification listeners. */
public interface SubstitutionOffers {

	Optional<Notice> notification(UUID requestId, int version);

	record Notice(UUID requestId, int version, String title, String rooms, Instant startsAt, Instant endsAt,
			List<UUID> recipientAccountIds) {
	}
}
