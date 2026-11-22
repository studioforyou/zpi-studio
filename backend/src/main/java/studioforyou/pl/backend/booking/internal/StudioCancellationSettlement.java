package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.util.Comparator;

import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.MakeupEntitlements;
import studioforyou.pl.backend.scheduling.ScheduleEventCancelled;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Returns allocations once, without promoting the queue of the cancelled event. */
@Component
class StudioCancellationSettlement {

	private final ReservationStore reservations;
	private final WaitlistStore waitlist;
	private final BookingEntitlements passes;
	private final MakeupEntitlements makeups;

	StudioCancellationSettlement(ReservationStore reservations, WaitlistStore waitlist,
			BookingEntitlements passes, MakeupEntitlements makeups) {
		this.reservations = reservations; this.waitlist = waitlist; this.passes = passes; this.makeups = makeups;
	}

	@EventListener
	@Transactional(propagation = Propagation.MANDATORY)
	void on(ScheduleEventCancelled event) {
		if (event.reason() != ScheduleEventCancelled.Reason.CANCELLED_BY_STUDIO) return;
		Instant now = Instant.now();
		var active = this.reservations.lockActiveInEvent(event.eventId());
		var queue = this.waitlist.lockOpenInEvent(event.eventId());
		for (var entry : queue) this.waitlist.cancel(entry.id(), now);
		var ordered = active.stream().sorted(Comparator.comparing((ReservationStore.Reservation r) ->
				r.customerPassId() == null ? r.makeupCreditId() : r.customerPassId(), Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(ReservationStore.Reservation::id)).toList();
		for (var reservation : ordered) {
			Boolean returned = null;
			if (reservation.customerPassId() != null) {
				returned = this.passes.returnForCancelledEvent(reservation.customerId(), reservation.customerPassId(), reservation.id(), event.cancelledBy());
			}
			else if (reservation.makeupCreditId() != null) {
				returned = this.makeups.canReturn(reservation.customerId(), reservation.makeupCreditId(), reservation.id(), now);
				this.makeups.release(reservation.customerId(), reservation.makeupCreditId(), reservation.id(), true, returned);
			}
			this.reservations.cancel(reservation, event.cancelledBy(), now, returned, "STUDIO");
		}
	}
}
