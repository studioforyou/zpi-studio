package studioforyou.pl.backend.booking.internal;

import java.time.Duration;
import java.util.UUID;

import studioforyou.pl.backend.scheduling.EventParticipantCounts;
import studioforyou.pl.backend.scheduling.ScheduleEventMoved;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What booking tells the schedule and how it follows the schedule's changes: the number of
 * places taken in an event, and free cancellation deadlines that move with a moved event.
 */
@Component
class ScheduleChangesForBooking implements EventParticipantCounts {

	private final ReservationStore reservations;

	ScheduleChangesForBooking(ReservationStore reservations) {
		this.reservations = reservations;
	}

	@Override
	public int activeParticipants(UUID eventId) {
		return this.reservations.activeCount(eventId);
	}

	/** Runs in the transaction that moved the event, which still holds the event's row lock. */
	@EventListener
	void onEventMoved(ScheduleEventMoved moved) {
		Duration shift = Duration.between(moved.previousStart(), moved.startsAt());
		if (!shift.isZero()) {
			this.reservations.shiftFreeCancelDeadlines(moved.eventId(), shift, moved.startsAt());
		}
	}

}
