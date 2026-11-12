package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** The caller's reserved personal sessions that the supplied staff member runs or assists. */
public interface PersonalTrainingSchedule {

	/** Booking supplies only events reserved by the already authorized participant. */
	List<GroupClassSchedule.Occurrence> findReserved(Collection<UUID> eventIds, @Nullable Instant from, @Nullable Instant to);

	/** A null window means all supplied events; otherwise returns overlap with [from, to). */
	List<GroupClassSchedule.Occurrence> findOwn(Collection<UUID> eventIds, UUID staffId,
			@Nullable Instant from, @Nullable Instant to);

}
