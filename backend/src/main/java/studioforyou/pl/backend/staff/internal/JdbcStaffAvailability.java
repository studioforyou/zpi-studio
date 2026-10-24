package studioforyou.pl.backend.staff.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.staff.StaffAvailability;

/** Reads staff leave and weekly working hours from the module's own tables. */
@Service
@Transactional(readOnly = true)
class JdbcStaffAvailability implements StaffAvailability {

	private final JdbcClient jdbc;

	JdbcStaffAvailability(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
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

	@Override
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

}
