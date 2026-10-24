package studioforyou.pl.backend.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * When staff members can work: their regular weekly hours and their leave. Other modules use it
 * to check whether a staff member can run an event at a given time.
 */
public interface StaffAvailability {

	/**
	 * Leave of the given staff members that overlaps {@code [from, to)} and is approved or still
	 * waiting for a decision. Rejected and cancelled requests are left out.
	 */
	List<TimeOff> findTimeOff(Collection<UUID> staffIds, Instant from, Instant to);

	/**
	 * Regular weekly working hours of the given staff members, in the studio's local time.
	 * A staff member without any rows has not set their availability.
	 */
	List<WeeklyHours> findWeeklyHours(Collection<UUID> staffIds);

	/**
	 * @param kind e.g. {@code VACATION} or {@code SICK_LEAVE}
	 * @param approved {@code true} when approved, {@code false} while it waits for a decision
	 */
	record TimeOff(UUID staffId, String kind, boolean approved, Instant startsAt, Instant endsAt) {

	}

	/**
	 * Hours a staff member works on one weekday, in the studio's local time.
	 *
	 * @param weekday ISO weekday, 1 = Monday
	 * @param validTo the last day the hours apply, or {@code null} with no end
	 */
	record WeeklyHours(UUID staffId, int weekday, LocalTime start, LocalTime end, LocalDate validFrom,
			@Nullable LocalDate validTo) {

		/** Whether these hours cover the whole time from {@code start} to {@code end} on the given day. */
		public boolean covers(LocalDate day, LocalTime start, LocalTime end) {
			return day.getDayOfWeek().getValue() == this.weekday && !day.isBefore(this.validFrom)
					&& (this.validTo == null || !day.isAfter(this.validTo)) && !start.isBefore(this.start)
					&& !end.isAfter(this.end);
		}

	}

}
