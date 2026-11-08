package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that changed the time or the capacity of one event, e.g. a
 * single occurrence of a series, while that transaction still holds the event's row lock.
 * Listeners can adjust what depends on the event's time or places.
 *
 * @param eventId the changed event
 * @param previousStart the start before the change
 * @param previousEnd the end before the change
 * @param previousCapacity the capacity before the change; {@code null} = no limit
 * @param startsAt the new start
 * @param endsAt the new end
 * @param capacity the new capacity; {@code null} = no limit
 */
public record ScheduleEventChanged(UUID eventId, Instant previousStart, Instant previousEnd,
		@Nullable Integer previousCapacity, Instant startsAt, Instant endsAt, @Nullable Integer capacity) {

	/** Whether the event starts or ends at another time. */
	public boolean moved() {
		return !this.previousStart.equals(this.startsAt) || !this.previousEnd.equals(this.endsAt);
	}

	/** Whether the event has more places than before, or no limit any more. */
	public boolean capacityRaised() {
		if (this.capacity == null) {
			return this.previousCapacity != null;
		}
		return this.previousCapacity != null && this.capacity > this.previousCapacity;
	}

}
