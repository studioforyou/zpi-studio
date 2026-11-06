package studioforyou.pl.backend.staff.internal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.AvailabilitySlotResponse;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.AvailabilityType;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.CreateAvailabilityRequest;
import studioforyou.pl.backend.staff.internal.AvailabilityDtos.UpdateAvailabilityRequest;

/**
 * A staff member's working hours: weekly hours and one-off extra days, stored in
 * {@code trainer_availability}. A one-off day is a row with {@code valid_from = valid_to}.
 *
 * <p>Hours that already applied before today are never rewritten: removing them ends them
 * yesterday and changing them ends them yesterday and starts a new row today, so past schedule
 * checks keep seeing what was true at the time. Changes to one staff member are serialized by
 * locking their profile row.
 */
@Service
@Transactional
class AvailabilitySlots {

	private final JdbcClient jdbc;

	private final StudioSettings studio;

	AvailabilitySlots(JdbcClient jdbc, StudioSettings studio) {
		this.jdbc = jdbc;
		this.studio = studio;
	}

	/** Hours that apply today or later: weekly ones by weekday, then one-off days by date. */
	@Transactional(readOnly = true)
	List<AvailabilitySlotResponse> list(UUID staffId) {
		boolean exists = this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM staff_profile WHERE id = :id)")
			.param("id", staffId)
			.query(Boolean.class)
			.single();
		if (!exists) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Staff member not found.");
		}
		return findCurrent(staffId, this.studio.today()).stream()
			.map(AvailabilitySlots::toResponse)
			.sorted(Comparator.comparing(AvailabilitySlotResponse::type)
				.thenComparing((slot) -> slot.type() == AvailabilityType.WEEKLY ? slot.weekday() : 0)
				.thenComparing((slot) -> slot.date() == null ? LocalDate.MIN : slot.date())
				.thenComparing(AvailabilitySlotResponse::startTime)
				.thenComparing(AvailabilitySlotResponse::validFrom))
			.toList();
	}

	/**
	 * Adds the same hours on several weekdays (repeating every week) or on several single
	 * dates, all or nothing.
	 */
	List<AvailabilitySlotResponse> create(UUID staffId, CreateAvailabilityRequest request, UUID actorAccountId) {
		LocalDate today = this.studio.today();
		requireTimeRange(request.startTime(), request.endTime());
		boolean weekly = request.weekdays() != null && !request.weekdays().isEmpty();
		boolean single = request.dates() != null && !request.dates().isEmpty();
		List<Row> rows;
		if (weekly && single) {
			throw ApiException.invalidField("dates", FieldErrorCode.INVALID_VALUE,
					"Send either weekdays or dates, not both.");
		}
		else if (single) {
			if (request.validFrom() != null || request.validTo() != null) {
				throw ApiException.invalidField("validFrom", FieldErrorCode.INVALID_VALUE,
						"validFrom and validTo apply only to weekly hours.");
			}
			if (request.dates().stream().anyMatch((date) -> date.isBefore(today))) {
				throw ApiException.invalidField("dates", FieldErrorCode.OUT_OF_RANGE, "Dates must be today or later.");
			}
			rows = request.dates().stream().distinct()
				.map((date) -> new Row(null, staffId, date.getDayOfWeek().getValue(), request.startTime(),
						request.endTime(), date, date))
				.toList();
		}
		else if (weekly) {
			LocalDate validFrom = (request.validFrom() != null) ? request.validFrom() : today;
			if (validFrom.isBefore(today)) {
				throw ApiException.invalidField("validFrom", FieldErrorCode.OUT_OF_RANGE,
						"validFrom must be today or later.");
			}
			requireWeeklyEnd(validFrom, request.validTo());
			rows = request.weekdays().stream().distinct()
				.map((weekday) -> new Row(null, staffId, weekday, request.startTime(), request.endTime(), validFrom,
						request.validTo()))
				.toList();
		}
		else {
			throw ApiException.invalidField("weekdays", FieldErrorCode.REQUIRED,
					"Send weekdays for weekly hours or dates for single days.");
		}
		lockStaff(staffId);
		List<AvailabilitySlotResponse> created = new ArrayList<>();
		for (Row row : rows) {
			rejectOverlap(row, null, today);
			created.add(toResponse(insert(row, actorAccountId)));
		}
		return created;
	}

	/**
	 * Changes the times or the end of a slot. Hours that already applied before today end
	 * yesterday and continue as a new slot from today, so the response may carry a new id.
	 */
	AvailabilitySlotResponse update(UUID staffId, UUID slotId, UpdateAvailabilityRequest request,
			UUID actorAccountId) {
		LocalDate today = this.studio.today();
		lockStaff(staffId);
		Row current = findCurrentById(staffId, slotId, today);
		LocalTime start = (request.getStartTime() != null) ? request.getStartTime() : current.start();
		LocalTime end = (request.getEndTime() != null) ? request.getEndTime() : current.end();
		LocalDate validTo = (request.getValidTo() != null) ? request.getValidTo().orElse(null) : current.validTo();
		requireTimeRange(start, end);
		Row changed = new Row(current.id(), staffId, current.weekday(), start, end, current.validFrom(), validTo);
		if (changed.equals(current)) {
			return toResponse(current);
		}
		// Only new times split the slot; a new end date is just set on it.
		boolean split = current.validFrom().isBefore(today)
				&& (!start.equals(current.start()) || !end.equals(current.end()));
		// A single day with an end date other than its own date becomes weekly from that day.
		if (!changed.oneOff()) {
			if (validTo != null && validTo.isBefore(today)) {
				throw ApiException.invalidField("validTo", FieldErrorCode.OUT_OF_RANGE,
						"validTo must be today or later.");
			}
			requireWeeklyEnd(split ? today : current.validFrom(), validTo);
		}
		rejectOverlap(changed, current.id(), today);
		if (split) {
			endYesterday(current.id(), today);
			return toResponse(insert(new Row(null, staffId, current.weekday(), start, end, today, validTo),
					actorAccountId));
		}
		this.jdbc.sql("""
				UPDATE trainer_availability
				SET local_start_time = :start, local_end_time = :end, valid_to = :validTo
				WHERE id = :id
				""")
			.param("start", start)
			.param("end", end)
			.param("validTo", validTo)
			.param("id", current.id())
			.update();
		return toResponse(changed);
	}

	/** Removes a slot from today on; hours that already applied before today end yesterday. */
	void delete(UUID staffId, UUID slotId) {
		LocalDate today = this.studio.today();
		lockStaff(staffId);
		Row current = findCurrentById(staffId, slotId, today);
		if (current.validFrom().isBefore(today)) {
			endYesterday(current.id(), today);
		}
		else {
			this.jdbc.sql("DELETE FROM trainer_availability WHERE id = :id").param("id", current.id()).update();
		}
	}

	/**
	 * Gives weekly hours other times on a single date only: the date is taken out of the weekly
	 * hours and gets its own one-off slot, which is returned. A one-off slot is just updated.
	 */
	AvailabilitySlotResponse updateOnDate(UUID staffId, UUID slotId, LocalDate date, UpdateAvailabilityRequest request,
			UUID actorAccountId) {
		LocalDate today = this.studio.today();
		lockStaff(staffId);
		Row current = findCurrentById(staffId, slotId, today);
		requireAppliesOn(current, date, today);
		if (current.oneOff()) {
			return update(staffId, slotId, request, actorAccountId);
		}
		if (request.getValidTo() != null) {
			throw ApiException.invalidField("validTo", FieldErrorCode.INVALID_VALUE,
					"A change on a single date has no end date.");
		}
		LocalTime start = (request.getStartTime() != null) ? request.getStartTime() : current.start();
		LocalTime end = (request.getEndTime() != null) ? request.getEndTime() : current.end();
		requireTimeRange(start, end);
		excludeDate(current, date, actorAccountId);
		Row day = new Row(null, staffId, current.weekday(), start, end, date, date);
		rejectOverlap(day, null, today);
		return toResponse(insert(day, actorAccountId));
	}

	/** Removes the hours on a single date only; weekly hours stay on the other weeks. */
	void deleteOnDate(UUID staffId, UUID slotId, LocalDate date, UUID actorAccountId) {
		LocalDate today = this.studio.today();
		lockStaff(staffId);
		Row current = findCurrentById(staffId, slotId, today);
		requireAppliesOn(current, date, today);
		if (current.oneOff()) {
			delete(staffId, slotId);
		}
		else {
			excludeDate(current, date, actorAccountId);
		}
	}

	private static void requireAppliesOn(Row row, LocalDate date, LocalDate today) {
		boolean applies = date.getDayOfWeek().getValue() == row.weekday() && !date.isBefore(row.validFrom())
				&& (row.validTo() == null || !date.isAfter(row.validTo()));
		if (date.isBefore(today) || !applies) {
			throw ApiException.invalidField("date", FieldErrorCode.OUT_OF_RANGE,
					"date must be today or later and a day on which the hours apply.");
		}
	}

	/**
	 * Splits weekly hours around one of their dates: the part before ends the day before, the
	 * part after starts a week later. A part without any of its weekdays left is dropped.
	 */
	private void excludeDate(Row row, LocalDate date, UUID actorAccountId) {
		boolean keepBefore = !row.validFrom().isAfter(date.minusDays(7));
		LocalDate nextWeek = date.plusDays(7);
		boolean keepAfter = row.validTo() == null || !row.validTo().isBefore(nextWeek);
		if (keepBefore) {
			this.jdbc.sql("UPDATE trainer_availability SET valid_to = :validTo WHERE id = :id")
				.param("validTo", date.minusDays(1))
				.param("id", row.id())
				.update();
			if (keepAfter) {
				insert(new Row(null, row.staffId(), row.weekday(), row.start(), row.end(), nextWeek, row.validTo()),
						actorAccountId);
			}
		}
		else if (keepAfter) {
			// The hours never applied before the date, so moving their start loses no history.
			this.jdbc.sql("UPDATE trainer_availability SET valid_from = :validFrom WHERE id = :id")
				.param("validFrom", nextWeek)
				.param("id", row.id())
				.update();
		}
		else {
			this.jdbc.sql("DELETE FROM trainer_availability WHERE id = :id").param("id", row.id()).update();
		}
	}

	private void lockStaff(UUID staffId) {
		this.jdbc.sql("SELECT id FROM staff_profile WHERE id = :id FOR UPDATE")
			.param("id", staffId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Staff member not found."));
	}

	private Row findCurrentById(UUID staffId, UUID slotId, LocalDate today) {
		return findCurrent(staffId, today).stream()
			.filter((row) -> row.id().equals(slotId))
			.findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Availability slot not found."));
	}

	private List<Row> findCurrent(UUID staffId, LocalDate today) {
		return this.jdbc.sql("""
				SELECT id, staff_id, weekday, local_start_time, local_end_time, valid_from, valid_to
				FROM trainer_availability
				WHERE staff_id = :staffId AND (valid_to IS NULL OR valid_to >= :today)
				""")
			.param("staffId", staffId)
			.param("today", today)
			.query((rs, rowNum) -> new Row(rs.getObject("id", UUID.class), rs.getObject("staff_id", UUID.class),
					rs.getInt("weekday"), rs.getObject("local_start_time", LocalTime.class),
					rs.getObject("local_end_time", LocalTime.class), rs.getObject("valid_from", LocalDate.class),
					rs.getObject("valid_to", LocalDate.class)))
			.list();
	}

	/**
	 * Rejects hours that overlap other hours of the staff member on the same weekday while both
	 * apply. Touching ranges such as 10:00-12:00 and 12:00-14:00 are fine.
	 */
	private void rejectOverlap(Row candidate, @Nullable UUID ignoredId, LocalDate today) {
		LocalDate from = candidate.validFrom().isBefore(today) ? today : candidate.validFrom();
		for (Row other : findCurrent(candidate.staffId(), today)) {
			if (other.id().equals(ignoredId) || other.weekday() != candidate.weekday()) {
				continue;
			}
			boolean timesOverlap = candidate.start().isBefore(other.end()) && other.start().isBefore(candidate.end());
			boolean datesOverlap = (other.validTo() == null || !other.validTo().isBefore(from))
					&& (candidate.validTo() == null || !candidate.validTo().isBefore(other.validFrom()));
			if (timesOverlap && datesOverlap) {
				throw new ApiException(ErrorCode.AVAILABILITY_OVERLAP,
						"These hours overlap other availability on the same day.",
						Map.of("conflictingSlotId", other.id()));
			}
		}
	}

	private Row insert(Row row, UUID actorAccountId) {
		UUID id = this.jdbc.sql("""
				INSERT INTO trainer_availability
				    (staff_id, weekday, local_start_time, local_end_time, valid_from, valid_to, created_by)
				VALUES (:staffId, :weekday, :start, :end, :validFrom, :validTo, :createdBy)
				RETURNING id
				""")
			.param("staffId", row.staffId())
			.param("weekday", row.weekday())
			.param("start", row.start())
			.param("end", row.end())
			.param("validFrom", row.validFrom())
			.param("validTo", row.validTo())
			.param("createdBy", actorAccountId)
			.query(UUID.class)
			.single();
		return new Row(id, row.staffId(), row.weekday(), row.start(), row.end(), row.validFrom(), row.validTo());
	}

	private void endYesterday(UUID id, LocalDate today) {
		this.jdbc.sql("UPDATE trainer_availability SET valid_to = :validTo WHERE id = :id")
			.param("validTo", today.minusDays(1))
			.param("id", id)
			.update();
	}

	private static void requireTimeRange(LocalTime start, LocalTime end) {
		if (start.getSecond() != 0 || start.getNano() != 0 || end.getSecond() != 0 || end.getNano() != 0) {
			throw ApiException.invalidField("startTime", FieldErrorCode.INVALID_FORMAT, "Times use whole minutes.");
		}
		if (!end.isAfter(start)) {
			throw ApiException.invalidField("endTime", FieldErrorCode.INVALID_VALUE,
					"endTime must be after startTime.");
		}
	}

	private static void requireWeeklyEnd(LocalDate validFrom, @Nullable LocalDate validTo) {
		// valid_from = valid_to marks a one-off day, so weekly hours need a longer period.
		if (validTo != null && !validTo.isAfter(validFrom)) {
			throw ApiException.invalidField("validTo", FieldErrorCode.OUT_OF_RANGE,
					"validTo must be after the first day the hours apply.");
		}
	}

	private static AvailabilitySlotResponse toResponse(Row row) {
		return new AvailabilitySlotResponse(row.id(), row.oneOff() ? AvailabilityType.ONE_OFF : AvailabilityType.WEEKLY,
				row.weekday(), row.oneOff() ? row.validFrom() : null, row.start(), row.end(), row.validFrom(),
				row.validTo());
	}

	private record Row(@Nullable UUID id, UUID staffId, int weekday, LocalTime start, LocalTime end,
			LocalDate validFrom, @Nullable LocalDate validTo) {

		boolean oneOff() {
			return this.validFrom.equals(this.validTo);
		}

	}

}
