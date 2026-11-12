package studioforyou.pl.backend.reporting.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.Participations.Cancellation;
import studioforyou.pl.backend.booking.Participations.EventOutcomes;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.reporting.internal.ReportQuery.ReportFilter;
import studioforyou.pl.backend.reporting.internal.StartedEvents.StartedEvent;

/**
 * What became of the places booked for events that took place in a period: attended, missed
 * without cancelling (no-shows), cancelled in time or cancelled late, with how long before the
 * start people cancelled and the service's current cancellation rules beside it, to judge whether
 * the rules work. The report's filter decides which events count; by default group classes and
 * workshops, so personal training does not skew their shares. An unmarked place counts as
 * attended, as when the trainer completes the event.
 */
@Service
class BookingOutcomesReport {

	/** Hours before the start where each lead-time range begins; the last one has no end. */
	static final int[] LEAD_TIME_BOUNDS = { 0, 2, 6, 12, 24, 48 };

	private final StartedEvents startedEvents;

	private final Participations participations;

	private final ServiceTypes serviceTypes;

	BookingOutcomesReport(StartedEvents startedEvents, Participations participations, ServiceTypes serviceTypes) {
		this.startedEvents = startedEvents;
		this.participations = participations;
		this.serviceTypes = serviceTypes;
	}

	Report build(ReportPeriod period, ReportFilter filter) {
		List<StartedEvent> events = this.startedEvents.find(period, filter);
		Tally totals = new Tally();
		Map<LocalDate, Tally> days = new TreeMap<>();
		// Keyed by service type; events without one share the null key.
		Map<@Nullable UUID, Tally> services = new LinkedHashMap<>();
		Map<@Nullable UUID, ServiceTypeSummary> summaries = new HashMap<>();
		for (StartedEvent started : events) {
			totals.add(started.outcomes());
			days.computeIfAbsent(started.localStart().toLocalDate(), (date) -> new Tally()).add(started.outcomes());
			UUID serviceTypeId = started.event().serviceTypeId();
			services.computeIfAbsent(serviceTypeId, (id) -> new Tally()).add(started.outcomes());
			if (started.service() != null) {
				summaries.put(serviceTypeId, started.service());
			}
		}
		List<ServiceRow> serviceRows = services.entrySet()
			.stream()
			.map((entry) -> serviceRow(entry.getKey(), summaries.get(entry.getKey()), entry.getValue().counts()))
			.sorted(Comparator.comparingInt((ServiceRow row) -> row.counts().bookings())
				.reversed()
				.thenComparing((row) -> row.name() == null ? "" : row.name()))
			.toList();
		return new Report(period.from(), period.to(), filter.sortedKinds(), totals.counts(),
				serviceRows, leadTimes(events),
				days.entrySet().stream().map((day) -> new DayRow(day.getKey(), day.getValue().counts())).toList());
	}

	private ServiceRow serviceRow(@Nullable UUID serviceTypeId, @Nullable ServiceTypeSummary summary, Counts counts) {
		Policy policy = serviceTypeId == null ? null
				: this.serviceTypes.findDefinitionById(serviceTypeId)
					.map(ServiceTypes.Definition::bookingPolicy)
					.map(BookingPolicies.Definition::effective)
					.map(Policy::of)
					.orElse(null);
		return new ServiceRow(serviceTypeId, summary == null ? null : summary.name(),
				summary == null ? null : summary.calendarColor(), counts, policy);
	}

	/** How long before the start of the events their places were cancelled. */
	private List<LeadTime> leadTimes(List<StartedEvent> events) {
		Map<UUID, Instant> starts = events.stream()
			.collect(Collectors.toMap((started) -> started.event().id(), (started) -> started.event().startsAt()));
		int[] inTime = new int[LEAD_TIME_BOUNDS.length];
		int[] late = new int[LEAD_TIME_BOUNDS.length];
		for (Cancellation cancellation : this.participations.findCancellations(starts.keySet())) {
			long minutes = Duration.between(cancellation.cancelledAt(), starts.get(cancellation.eventId())).toMinutes();
			int range = 0;
			while (range + 1 < LEAD_TIME_BOUNDS.length && minutes >= LEAD_TIME_BOUNDS[range + 1] * 60L) {
				range++;
			}
			if (cancellation.late()) {
				late[range]++;
			}
			else {
				inTime[range]++;
			}
		}
		List<LeadTime> ranges = new ArrayList<>();
		for (int range = 0; range < LEAD_TIME_BOUNDS.length; range++) {
			ranges.add(new LeadTime(LEAD_TIME_BOUNDS[range],
					range + 1 < LEAD_TIME_BOUNDS.length ? LEAD_TIME_BOUNDS[range + 1] : null, inTime[range],
					late[range]));
		}
		return ranges;
	}

	/**
	 * @param events events that took place
	 * @param bookings places booked for them, cancelled ones too: attended, absent and both kinds of
	 * cancellations together
	 * @param attended places of participants not marked absent
	 * @param absent places of participants marked absent: they did not come and did not cancel
	 * @param unmarked attended places nobody marked present or absent
	 * @param cancelledInTime places cancelled before the free cancellation deadline
	 * @param cancelledLate places cancelled after it
	 * @param lateEntriesForfeited late cancellations whose pass entry was not returned
	 */
	@Schema(name = "BookingOutcomeCounts")
	record Counts(@Schema(example = "12") int events,
			@Schema(description = "Places booked for the events, cancelled ones too: attended + absent + "
					+ "cancelledInTime + cancelledLate.", example = "104") int bookings,
			@Schema(description = "Places of participants not marked absent; an unmarked place counts as attended.",
					example = "79") int attended,
			@Schema(description = "Places of participants marked absent: no-shows.", example = "7") int absent,
			@Schema(description = "Attended places nobody marked present or absent.", example = "3") int unmarked,
			@Schema(description = "Places cancelled before the free cancellation deadline.",
					example = "12") int cancelledInTime,
			@Schema(description = "Places cancelled after the free cancellation deadline.",
					example = "6") int cancelledLate,
			@Schema(description = "Late cancellations whose pass entry was not returned.",
					example = "5") int lateEntriesForfeited) {
	}

	/** The service's cancellation rules in force now; they may have changed during the period. */
	@Schema(name = "BookingOutcomePolicy",
			description = "The service's effective cancellation rules now; they may have changed during the period.")
	record Policy(@Nullable Boolean cancellationAllowed, @Nullable @Schema(example = "12") Integer freeCancelHours,
			@Nullable Boolean lateCancelAllowed, @Nullable Boolean lateCancelForfeitsEntry) {

		static Policy of(BookingPolicies.Values values) {
			return new Policy(values.cancellationAllowed(), values.freeCancelHours(), values.lateCancelAllowed(),
					values.lateCancelForfeitsEntry());
		}

	}

	/** @param serviceTypeId {@code null} for events without a service type */
	@Schema(name = "BookingOutcomeService")
	record ServiceRow(@Nullable @Schema(description = "Null for events without a service type.") UUID serviceTypeId,
			@Nullable @Schema(example = "Pole dance") String name,
			@Nullable @Schema(example = "#E64980") String calendarColor, Counts counts,
			@Nullable @Schema(description = "Null when the service type is unknown.") Policy policy) {
	}

	/**
	 * Cancellations made between {@code fromHours} and {@code toHours} before the start.
	 *
	 * @param toHours {@code null} for the last, open-ended range
	 */
	@Schema(name = "CancellationLeadTime",
			description = "Places cancelled at least fromHours and less than toHours before the start.")
	record LeadTime(@Schema(example = "12") int fromHours,
			@Nullable @Schema(description = "Null for the last, open-ended range.", example = "24") Integer toHours,
			@Schema(example = "4") int inTime, @Schema(example = "2") int late) {
	}

	@Schema(name = "BookingOutcomeDay", description = "Events that started on one studio date.")
	record DayRow(LocalDate date, Counts counts) {
	}

	@Schema(name = "BookingOutcomesReport")
	record Report(LocalDate from, LocalDate to,
			@Schema(description = "Kinds of events the report covers.") List<ReportEventKind> kinds, Counts totals,
			@Schema(description = "Most bookings first.") List<ServiceRow> services,
			@Schema(description = "From the shortest time before the start to the longest.")
			List<LeadTime> cancellationLeadTimes,
			@Schema(description = "Only dates with events, earliest first.") List<DayRow> days) {
	}

	private static final class Tally {

		private int events;

		private int booked;

		private int absent;

		private int unmarked;

		private int cancelledInTime;

		private int cancelledLate;

		private int lateEntriesForfeited;

		void add(EventOutcomes outcomes) {
			this.events++;
			this.booked += outcomes.booked();
			this.absent += outcomes.absent();
			this.unmarked += outcomes.unmarked();
			this.cancelledInTime += outcomes.cancelledInTime();
			this.cancelledLate += outcomes.cancelledLate();
			this.lateEntriesForfeited += outcomes.lateEntriesForfeited();
		}

		Counts counts() {
			return new Counts(this.events, this.booked + this.cancelledInTime + this.cancelledLate,
					this.booked - this.absent, this.absent, this.unmarked, this.cancelledInTime, this.cancelledLate,
					this.lateEntriesForfeited);
		}

	}

}
