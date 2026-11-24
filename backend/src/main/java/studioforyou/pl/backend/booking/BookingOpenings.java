package studioforyou.pl.backend.booking;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;

/**
 * Group classes whose booking has just opened, e.g. to tell the clients interested in them. A
 * class's booking opens {@code bookingOpensDaysBefore} days before its start, as its booking policy
 * says; for a class added to the schedule later than that, when it was added.
 */
public interface BookingOpenings {

	/**
	 * Public group classes whose booking opened in {@code (from, to]} and that can still be booked
	 * at {@code to}: scheduled, not started, booking not closed and with a free place.
	 */
	List<OpenedClass> findOpenedBetween(Instant from, Instant to);

	/**
	 * @param event the class
	 * @param opensAt when its booking opened
	 * @param takingPart participants with an active reservation for it or on its waiting list
	 * (waiting or holding an offered place)
	 */
	record OpenedClass(Occurrence event, Instant opensAt, Set<UUID> takingPart) {

		public OpenedClass {
			takingPart = Set.copyOf(takingPart);
		}

	}

}
