package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.IssueType;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleIssue;
import studioforyou.pl.backend.scheduling.internal.ScheduleQueries.ResourceUsage;
import studioforyou.pl.backend.scheduling.internal.ScheduleQueries.StaffUsage;
import studioforyou.pl.backend.staff.StaffAvailability;
import studioforyou.pl.backend.staff.StaffAvailability.TimeOff;
import studioforyou.pl.backend.staff.StaffAvailability.WeeklyHours;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.stereotype.Component;

/**
 * Finds what stands in the way of planned occurrences: rooms taken by other events, staff members who run another event or are on leave, and softer problems such as a
 * time outside a trainer's regular hours.
 */
@Component
class ScheduleConflicts {

	private final ScheduleQueries queries;

	private final StaffAvailability availability;

	private final StaffProfiles staffProfiles;

	ScheduleConflicts(ScheduleQueries queries, StaffAvailability availability, StaffProfiles staffProfiles) {
		this.queries = queries;
		this.availability = availability;
		this.staffProfiles = staffProfiles;
	}

	/**
	 * The problems of each occurrence, in the order of {@code occurrences}.
	 *
	 * @param ignoredEventIds events whose own assignments are being replaced, so they do not
	 * conflict with themselves
	 */
	List<List<ScheduleIssue>> find(List<Occurrence> occurrences, Assignments assignments, ZoneId zone,
			Collection<UUID> ignoredEventIds) {
		List<List<ScheduleIssue>> result = new ArrayList<>();
		if (occurrences.isEmpty()) {
			return result;
		}
		Instant from = occurrences.stream().map(Occurrence::startsAt).min(Comparator.naturalOrder()).orElseThrow();
		Instant to = occurrences.stream().map(Occurrence::endsAt).max(Comparator.naturalOrder()).orElseThrow();
		Set<UUID> staffIds = assignments.staffIds();
		Map<UUID, Boolean> blocksByResource = assignments.resources()
			.stream()
			.collect(Collectors.toMap(EventResourceAssignment::resourceId, EventResourceAssignment::blocksWholeRoom));

		List<ResourceUsage> resourceUsage = this.queries.findResourceUsage(blocksByResource.keySet(), from, to,
				ignoredEventIds);
		List<StaffUsage> staffUsage = this.queries.findStaffUsage(staffIds, from, to, ignoredEventIds);
		List<TimeOff> timeOff = this.availability.findTimeOff(staffIds, from, to);
		Map<UUID, List<WeeklyHours>> hours = this.availability.findWeeklyHours(staffIds)
			.stream()
			.collect(Collectors.groupingBy(WeeklyHours::staffId));

		for (Occurrence occurrence : occurrences) {
			List<ScheduleIssue> issues = new ArrayList<>();
			for (ResourceUsage usage : resourceUsage) {
				boolean shared = !blocksByResource.get(usage.resourceId()) && !usage.blocksWholeRoom();
				if (!shared && occurrence.overlaps(usage.startsAt(), usage.endsAt())) {
					issues.add(new ScheduleIssue(IssueType.RESOURCE_BUSY, true, null, usage.resourceId(),
							usage.eventId(), usage.title(), usage.startsAt(), usage.endsAt()));
				}
			}
			for (StaffUsage usage : staffUsage) {
				if (occurrence.overlaps(usage.startsAt(), usage.endsAt())) {
					issues.add(new ScheduleIssue(IssueType.STAFF_BUSY, true, usage.staffId(), null, usage.eventId(),
							usage.title(), usage.startsAt(), usage.endsAt()));
				}
			}
			for (TimeOff leave : timeOff) {
				if (occurrence.overlaps(leave.startsAt(), leave.endsAt())) {
					issues.add(new ScheduleIssue(
							leave.approved() ? IssueType.STAFF_ON_LEAVE : IssueType.STAFF_LEAVE_REQUESTED,
							leave.approved(), leave.staffId(), null, null, null, leave.startsAt(), leave.endsAt()));
				}
			}
			hours.forEach((staffId, staffHours) -> {
				if (!withinHours(occurrence, staffHours, zone)) {
					issues.add(new ScheduleIssue(IssueType.STAFF_OUTSIDE_AVAILABILITY, false, staffId, null, null, null,
							null, null));
				}
			});
			result.add(issues);
		}
		return result;
	}

	/**
	 * Problems of the plan as a whole that do not block saving: staff members not qualified to
	 * run the service type.
	 */
	List<ScheduleIssue> warnings(UUID serviceTypeId, Assignments assignments) {
		if (serviceTypeId == null || assignments.staffIds().isEmpty()) {
			return List.of();
		}
		Map<UUID, Set<UUID>> qualifications = this.staffProfiles.findQualifications(assignments.staffIds());
		return assignments.staff()
			.stream()
			.map(EventStaffAssignment::staffId)
			.filter((staffId) -> !qualifications.getOrDefault(staffId, Set.of()).contains(serviceTypeId))
			.map((staffId) -> new ScheduleIssue(IssueType.STAFF_NOT_QUALIFIED, false, staffId, null, null, null, null,
					null))
			.toList();
	}

	/**
	 * Whether the staff member's regular hours cover the occurrence. An occurrence that ends on
	 * another day than it starts is never covered.
	 */
	private static boolean withinHours(Occurrence occurrence, List<WeeklyHours> staffHours, ZoneId zone) {
		ZonedDateTime start = occurrence.startsAt().atZone(zone);
		ZonedDateTime end = occurrence.endsAt().atZone(zone);
		LocalDate day = start.toLocalDate();
		if (!end.toLocalDate().equals(day) && !end.toLocalTime().equals(LocalTime.MIDNIGHT)) {
			return false;
		}
		LocalTime endTime = end.toLocalDate().equals(day) ? end.toLocalTime() : LocalTime.MAX;
		return staffHours.stream().anyMatch((hours) -> hours.covers(day, start.toLocalTime(), endTime));
	}

	/**
	 * One planned occurrence.
	 *
	 * @param date the studio local date it starts on
	 * @param excluded whether the plan leaves the date out
	 */
	record Occurrence(LocalDate date, Instant startsAt, Instant endsAt, boolean excluded) {

		boolean overlaps(Instant otherStart, Instant otherEnd) {
			return this.startsAt.isBefore(otherEnd) && otherStart.isBefore(this.endsAt);
		}

	}

	/**
	 * The staff and rooms assigned to planned occurrences, already checked to exist and be active.
	 */
	record Assignments(List<EventStaffAssignment> staff, List<EventResourceAssignment> resources,
			Map<UUID, Resource> resourcesById) {

		Set<UUID> staffIds() {
			return this.staff.stream().map(EventStaffAssignment::staffId).collect(Collectors.toSet());
		}

		Set<UUID> resourceIds() {
			return this.resources.stream().map(EventResourceAssignment::resourceId).collect(Collectors.toSet());
		}

		/** The lead staff member, or the first assigned one when there is no lead. */
		UUID leadStaffId() {
			return this.staff.stream()
				.filter((assignment) -> assignment.role() == StaffRole.LEAD)
				.findFirst()
				.or(() -> this.staff.stream().findFirst())
				.map(EventStaffAssignment::staffId)
				.orElse(null);
		}

		/** The first assigned room, or {@code null} when there is none. */
		UUID mainResourceId() {
			return this.resources.stream().findFirst().map(EventResourceAssignment::resourceId).orElse(null);
		}

	}

}
