package studioforyou.pl.backend.staff;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * When staff members can work: their regular weekly hours and their leave. Other modules use it
 * to check whether a staff member can run an event at a given time.
 */
@Repository
public class StaffAvailability {

	private final JdbcClient jdbc;

	public StaffAvailability(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Leave of the given staff members that overlaps {@code [from, to)} and is approved or still
	 * waiting for a decision. Rejected and cancelled requests are left out.
	 */
	public List<TimeOff> findTimeOff(Collection<UUID> staffIds, Instant from, Instant to) {
		if (staffIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT staff_id, kind, status, starts_at, ends_at
				FROM staff_time_off
				WHERE staff_id IN (:staffIds)
				  AND status IN ('APPROVED', 'REQUESTED')
				  AND starts_at < :to AND ends_at > :from
				ORDER BY starts_at
				""")
			.param("staffIds", staffIds)
			.param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
			.param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
			.query((rs, rowNum) -> new TimeOff(rs.getObject("staff_id", UUID.class), rs.getString("kind"),
					"APPROVED".equals(rs.getString("status")),
					rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
					rs.getObject("ends_at", OffsetDateTime.class).toInstant()))
			.list();
	}

	/**
	 * Regular weekly working hours of the given staff members, in the studio's local time.
	 * A staff member without any rows has not set their availability.
	 */
	public List<WeeklyHours> findWeeklyHours(Collection<UUID> staffIds) {
		if (staffIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT staff_id, weekday, local_start_time, local_end_time, valid_from, valid_to
				FROM trainer_availability
				WHERE staff_id IN (:staffIds)
				""")
			.param("staffIds", staffIds)
			.query((rs, rowNum) -> new WeeklyHours(rs.getObject("staff_id", UUID.class), rs.getInt("weekday"),
					rs.getObject("local_start_time", LocalTime.class), rs.getObject("local_end_time", LocalTime.class),
					rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class)))
			.list();
	}

	/**
	 * @param kind e.g. {@code VACATION} or {@code SICK_LEAVE}
	 * @param approved {@code true} when approved, {@code false} while it waits for a decision
	 */
	public record TimeOff(UUID staffId, String kind, boolean approved, Instant startsAt, Instant endsAt) {

	}

	/**
	 * Hours a staff member works on one weekday, in the studio's local time.
	 *
	 * @param weekday ISO weekday, 1 = Monday
	 * @param validTo the last day the hours apply, or {@code null} with no end
	 */
	public record WeeklyHours(UUID staffId, int weekday, LocalTime start, LocalTime end, LocalDate validFrom,
			@Nullable LocalDate validTo) {

		/** Whether these hours cover the whole time from {@code start} to {@code end} on the given day. */
		public boolean covers(LocalDate day, LocalTime start, LocalTime end) {
			return day.getDayOfWeek().getValue() == this.weekday && !day.isBefore(this.validFrom)
					&& (this.validTo == null || !day.isAfter(this.validTo)) && !start.isBefore(this.start)
					&& !end.isAfter(this.end);
		}

	}

}
