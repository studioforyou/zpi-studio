package studioforyou.pl.backend.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.BookingOpenings;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.scheduling.GroupClassSchedule;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;

/**
 * Finds the classes whose booking opened ({@link BookingOpenings}). Every public class that can
 * still open within the longest booking window of any policy is checked against its own policy,
 * like the automatic cancellation checks its candidates.
 */
@Component
class DefaultBookingOpenings implements BookingOpenings {

	private final GroupClassSchedule schedule;

	private final BookingPolicies policies;

	private final ReservationStore reservations;

	private final WaitlistStore waitlist;

	private final JdbcClient jdbc;

	DefaultBookingOpenings(GroupClassSchedule schedule, BookingPolicies policies, ReservationStore reservations,
			WaitlistStore waitlist, JdbcClient jdbc) {
		this.schedule = schedule;
		this.policies = policies;
		this.reservations = reservations;
		this.waitlist = waitlist;
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<OpenedClass> findOpenedBetween(Instant from, Instant to) {
		if (!from.isBefore(to)) {
			return List.of();
		}
		// A class whose booking opened by `to` starts at most the longest booking window after it.
		Duration longest = Duration.ofDays(this.policies.longestBookingOpensDaysBefore());
		List<Occurrence> candidates = this.schedule.list(to, to.plus(longest).plusSeconds(1))
			.stream()
			.filter((event) -> "SCHEDULED".equals(event.status()) && event.startsAt().isAfter(to))
			.toList();
		if (candidates.isEmpty()) {
			return List.of();
		}
		Map<UUID, Instant> addedAt = addedAt(candidates.stream().map(Occurrence::id).toList());
		List<Occurrence> opened = new ArrayList<>();
		Map<UUID, Instant> opensAt = new HashMap<>();
		for (Occurrence event : candidates) {
			BookingPolicies.Values rules = this.policies
				.forOccurrence(event.serviceTypeId(), event.trainingGroupId(), event.id())
				.effective();
			Instant opens = opensAt(event.startsAt(), rules, addedAt.get(event.id()));
			if (!opens.isAfter(from) || opens.isAfter(to) || ParticipationRules.windowReason(event, rules, to) != null
					|| !hasFreePlace(event, to)) {
				continue;
			}
			opened.add(event);
			opensAt.put(event.id(), opens);
		}
		if (opened.isEmpty()) {
			return List.of();
		}
		Map<UUID, Set<UUID>> takingPart = takingPart(opened.stream().map(Occurrence::id).toList());
		return opened.stream()
			.map((event) -> new OpenedClass(event, opensAt.get(event.id()),
					takingPart.getOrDefault(event.id(), Set.of())))
			.toList();
	}

	/**
	 * When booking of a class opens: {@code bookingOpensDaysBefore} days before its start, or when
	 * it was added to the schedule if that was later.
	 */
	static Instant opensAt(Instant startsAt, BookingPolicies.Values rules, Instant addedAt) {
		Instant opens = startsAt.minus(Duration.ofDays(rules.bookingOpensDaysBefore()));
		return (addedAt != null && addedAt.isAfter(opens)) ? addedAt : opens;
	}

	private boolean hasFreePlace(Occurrence event, Instant now) {
		return event.capacity() == null || this.reservations.activeCount(event.id())
				+ this.waitlist.heldOffers(event.id(), now) < event.capacity();
	}

	private Map<UUID, Instant> addedAt(List<UUID> eventIds) {
		return this.jdbc.sql("SELECT id, created_at FROM schedule_event WHERE id IN (:ids)")
			.param("ids", eventIds)
			.query((rs, row) -> Map.entry(rs.getObject("id", UUID.class),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private Map<UUID, Set<UUID>> takingPart(List<UUID> eventIds) {
		Map<UUID, Set<UUID>> result = new HashMap<>();
		this.jdbc.sql("""
				SELECT event_id, customer_id FROM reservation
				WHERE event_id IN (:ids) AND status = 'ACTIVE'
				UNION
				SELECT event_id, customer_id FROM waitlist_entry
				WHERE event_id IN (:ids) AND status IN ('WAITING', 'OFFERED')
				""")
			.param("ids", eventIds)
			.query((rs, row) -> Map.entry(rs.getObject("event_id", UUID.class), rs.getObject("customer_id", UUID.class)))
			.list()
			.forEach((entry) -> result.computeIfAbsent(entry.getKey(), (key) -> new HashSet<>()).add(entry.getValue()));
		return result;
	}

}
