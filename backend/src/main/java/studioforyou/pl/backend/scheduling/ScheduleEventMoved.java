package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.UUID;

/**
 * Published inside the transaction that moves one event, e.g. a single occurrence of a series,
 * to another time. Listeners can adjust what depends on the event's time.
 *
 * @param eventId the moved event
 * @param previousStart the start before the change
 * @param previousEnd the end before the change
 * @param startsAt the new start
 * @param endsAt the new end
 */
public record ScheduleEventMoved(UUID eventId, Instant previousStart, Instant previousEnd, Instant startsAt,
		Instant endsAt) {
}
