package studioforyou.pl.backend.reporting.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;

import studioforyou.pl.backend.booking.Participations.EventOutcomes;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.reporting.internal.ReportQuery.ReportFilter;
import studioforyou.pl.backend.reporting.internal.StartedEvents.StartedEvent;
import studioforyou.pl.backend.scheduling.ScheduledEvents.ReportedEvent;
import studioforyou.pl.backend.trainings.TrainingGroups;

/**
 * Attendance and occupancy of group classes and workshops that took place in a period.
 * <ul>
 * <li>A participant <em>attended</em> unless marked absent: an unmarked place counts as present,
 * as it does when the trainer completes the class. Unmarked places are also counted on their own,
 * so missing attendance shows.</li>
 * <li><em>Occupancy</em> compares the places booked with the places offered, only for classes with
 * a limit of places.</li>
 * </ul>
 * One class is a recurring series, so all its occurrences in the period make one row; a one-off
 * event is a class of its own. The report's filter decides which events count.
 */
@Service
class ClassAttendanceReport {

	private final StartedEvents startedEvents;

	private final TrainingGroups trainingGroups;

	ClassAttendanceReport(StartedEvents startedEvents, TrainingGroups trainingGroups) {
		this.startedEvents = startedEvents;
		this.trainingGroups = trainingGroups;
	}

	/** @param filter the events to report, of the {@link ReportEventKind#GROUP} kinds only */
	Report build(ReportPeriod period, ReportFilter filter) {
		List<StartedEvent> events = this.startedEvents.find(period, filter);
		Map<UUID, String> groupNames = this.trainingGroups.names(events.stream()
			.map((started) -> started.event().trainingGroupId())
			.filter(Objects::nonNull)
			.collect(Collectors.toSet()));
		Tally totals = new Tally();
		Map<UUID, ClassTally> classes = new LinkedHashMap<>();
		Map<LocalDate, Tally> days = new TreeMap<>();
		for (StartedEvent started : events) {
			totals.add(started);
			days.computeIfAbsent(started.localStart().toLocalDate(), (date) -> new Tally()).add(started);
			ReportedEvent event = started.event();
			classes.computeIfAbsent(event.seriesId() != null ? event.seriesId() : event.id(), (id) -> new ClassTally())
				.add(started);
		}
		List<ClassRow> rows = classes.entrySet()
			.stream()
			.map((entry) -> entry.getValue().row(entry.getKey(), groupNames))
			.sorted(Comparator.comparingInt(ClassRow::weekday)
				.thenComparing(ClassRow::startTime)
				.thenComparing(ClassRow::title)
				.thenComparing(ClassRow::id))
			.toList();
		return new Report(period.from(), period.to(), filter.sortedKinds(), totals.counts(), rows,
				days.entrySet().stream().map((day) -> new DayRow(day.getKey(), day.getValue().counts())).toList());
	}

	/**
	 * @param events classes that took place
	 * @param booked places not cancelled
	 * @param attended booked places of participants not marked absent
	 * @param absent booked places of participants marked absent: they did not come and did not cancel
	 * @param unmarked attended places nobody marked present or absent
	 * @param places the places offered by the classes with a limit
	 * @param placesBooked booked places of the classes with a limit
	 * @param waitlisted participants who joined a waiting list
	 */
	@Schema(name = "ClassAttendanceCounts")
	record Counts(@Schema(example = "12") int events,
			@Schema(description = "Places not cancelled.", example = "86") int booked,
			@Schema(description = "Booked places of participants not marked absent; an unmarked place counts as "
					+ "attended.", example = "79") int attended,
			@Schema(description = "Booked places of participants marked absent (no-shows).", example = "7") int absent,
			@Schema(description = "Attended places nobody marked present or absent.", example = "3") int unmarked,
			@Schema(description = "Places offered by the classes with a limit of places.", example = "120") int places,
			@Schema(description = "Booked places of the classes with a limit; occupancy is placesBooked / places.",
					example = "86") int placesBooked,
			@Schema(description = "Participants who joined a waiting list.", example = "4") int waitlisted) {
	}

	/**
	 * @param id the recurring series, or the event for a one-off class
	 * @param title the title of its latest occurrence, or its service type's name
	 * @param weekday ISO day of the week its occurrences mostly start on, 1 = Monday
	 * @param startTime the studio time its occurrences mostly start at
	 */
	@Schema(name = "ClassAttendanceClass",
			description = "One class: a recurring series with all its occurrences in the period, or a one-off event.")
	record ClassRow(@Schema(description = "The recurring series, or the event of a one-off class.") UUID id,
			@Nullable UUID seriesId, @Schema(example = "Pole dance — podstawy") String title,
			@Nullable UUID serviceTypeId, @Nullable @Schema(example = "Pole dance") String serviceTypeName,
			@Nullable @Schema(example = "#E64980") String calendarColor, @Nullable UUID trainingGroupId,
			@Nullable String trainingGroupName,
			@Schema(description = "ISO weekday its occurrences mostly start on, 1 = Monday.", example = "2") int weekday,
			@JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "18:00",
					description = "Studio local time its occurrences mostly start at.") LocalTime startTime,
			Counts counts) {
	}

	@Schema(name = "ClassAttendanceDay", description = "Classes that started on one studio date.")
	record DayRow(LocalDate date, Counts counts) {
	}

	@Schema(name = "ClassAttendanceReport")
	record Report(LocalDate from, LocalDate to,
			@Schema(description = "Kinds of events the report covers.") List<ReportEventKind> kinds, Counts totals,
			List<ClassRow> classes,
			@Schema(description = "Only dates with classes, earliest first.") List<DayRow> days) {
	}

	private static class Tally {

		private int events;

		private int booked;

		private int absent;

		private int unmarked;

		private int places;

		private int placesBooked;

		private int waitlisted;

		void add(StartedEvent started) {
			EventOutcomes outcomes = started.outcomes();
			this.events++;
			this.booked += outcomes.booked();
			this.absent += outcomes.absent();
			this.unmarked += outcomes.unmarked();
			this.waitlisted += outcomes.waitlisted();
			Integer capacity = started.event().capacity();
			if (capacity != null) {
				this.places += capacity;
				this.placesBooked += outcomes.booked();
			}
		}

		Counts counts() {
			return new Counts(this.events, this.booked, this.booked - this.absent, this.absent, this.unmarked,
					this.places, this.placesBooked, this.waitlisted);
		}

	}

	private static final class ClassTally extends Tally {

		private final Map<Slot, Integer> slots = new HashMap<>();

		private @Nullable StartedEvent latest;

		@Override
		void add(StartedEvent started) {
			super.add(started);
			this.slots.merge(new Slot(started.localStart().getDayOfWeek(), started.localStart().toLocalTime()), 1,
					Integer::sum);
			// Events come earliest first.
			this.latest = started;
		}

		ClassRow row(UUID id, Map<UUID, String> groupNames) {
			StartedEvent last = Objects.requireNonNull(this.latest);
			Slot lastSlot = new Slot(last.localStart().getDayOfWeek(), last.localStart().toLocalTime());
			// The most frequent start; on a tie, the latest occurrence's.
			Slot slot = this.slots.entrySet()
				.stream()
				.max(Comparator.<Map.Entry<Slot, Integer>>comparingInt(Map.Entry::getValue)
					.thenComparing((entry) -> entry.getKey().equals(lastSlot)))
				.map(Map.Entry::getKey)
				.orElse(lastSlot);
			ReportedEvent event = last.event();
			ServiceTypeSummary service = last.service();
			UUID groupId = event.trainingGroupId();
			return new ClassRow(id, event.seriesId(), last.title(), event.serviceTypeId(),
					service == null ? null : service.name(), service == null ? null : service.calendarColor(), groupId,
					groupId == null ? null : groupNames.get(groupId), slot.day().getValue(), slot.time(), counts());
		}

	}

	private record Slot(DayOfWeek day, LocalTime time) {
	}
}
