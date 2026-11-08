package studioforyou.pl.backend.booking.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import studioforyou.pl.backend.scheduling.EventParticipantCounts;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.ScheduleEventChanged;
import studioforyou.pl.backend.shared.api.ApiException;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What booking tells the schedule and how it follows the schedule's changes: the places taken in
 * an event, free cancellation deadlines that move with a moved event, and places a raised
 * capacity gives to the waiting list.
 */
@Component
class ScheduleChangesForBooking implements EventParticipantCounts {

	private final ReservationStore reservations;

	private final WaitlistStore waitlist;

	private final GroupClassSchedule schedule;

	private final WaitlistPromotion promotion;

	private final Clock clock = Clock.systemUTC();

	ScheduleChangesForBooking(ReservationStore reservations, WaitlistStore waitlist, GroupClassSchedule schedule,
			WaitlistPromotion promotion) {
		this.reservations = reservations;
		this.waitlist = waitlist;
		this.schedule = schedule;
		this.promotion = promotion;
	}

	/** Active reservations and places held for waiting-list offers that have not run out. */
	@Override
	public int activeParticipants(UUID eventId) {
		return this.reservations.activeCount(eventId) + this.waitlist.heldOffers(eventId, this.clock.instant());
	}

	/** Runs in the transaction that changed the event, which still holds the event's row lock. */
	@EventListener
	void onEventChanged(ScheduleEventChanged change) {
		if (change.moved()) {
			Duration shift = Duration.between(change.previousStart(), change.startsAt());
			if (!shift.isZero()) {
				this.reservations.shiftFreeCancelDeadlines(change.eventId(), shift, change.startsAt());
			}
		}
		if (change.capacityRaised()) {
			fillRaisedCapacity(change.eventId());
		}
	}

	/** New places of a public group class go to its waiting list first, as freed places do. */
	private void fillRaisedCapacity(UUID eventId) {
		Occurrence event;
		try {
			event = this.schedule.lock(eventId);
		}
		catch (ApiException ex) {
			// Not a public group class: it has no waiting list.
			return;
		}
		Instant now = this.clock.instant();
		this.promotion.fillFreePlaces(event, null, now, List.of());
	}

}
