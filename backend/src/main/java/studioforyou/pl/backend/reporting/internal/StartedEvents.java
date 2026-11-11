package studioforyou.pl.backend.reporting.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.booking.Participations;
import studioforyou.pl.backend.booking.Participations.EventOutcomes;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.reporting.internal.ReportQuery.ReportFilter;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.ReportedEvent;

/**
 * Events of a period that already started, were not cancelled and match the report's filter,
 * with what became of their places: what the attendance and cancellation reports are made of. An
 * event that has not started has no attendance yet, so a period reaching into the future ends now.
 */
@Component
class StartedEvents {

	private final StudioSettings studioSettings;

	private final ScheduledEvents scheduledEvents;

	private final Participations participations;

	private final ServiceTypes serviceTypes;

	private final Clock clock = Clock.systemUTC();

	StartedEvents(StudioSettings studioSettings, ScheduledEvents scheduledEvents, Participations participations,
			ServiceTypes serviceTypes) {
		this.studioSettings = studioSettings;
		this.scheduledEvents = scheduledEvents;
		this.participations = participations;
		this.serviceTypes = serviceTypes;
	}

	List<StartedEvent> find(ReportPeriod period, ReportFilter filter) {
		ZoneId zone = this.studioSettings.zone();
		Instant now = this.clock.instant();
		Instant end = period.end(zone).isAfter(now) ? now : period.end(zone);
		List<ReportedEvent> events = this.scheduledEvents.findNotCancelledStartingBetween(period.start(zone), end,
				filter.toEventFilter());
		Map<UUID, EventOutcomes> outcomes = this.participations
			.countOutcomes(events.stream().map(ReportedEvent::id).toList());
		Map<UUID, ServiceTypeSummary> services = this.serviceTypes
			.findAllById(events.stream().map(ReportedEvent::serviceTypeId).filter(Objects::nonNull).distinct().toList())
			.stream()
			.collect(Collectors.toMap(ServiceTypeSummary::id, Function.identity()));
		return events.stream()
			.map((event) -> new StartedEvent(event, LocalDateTime.ofInstant(event.startsAt(), zone),
					outcomes.getOrDefault(event.id(), EventOutcomes.NONE),
					event.serviceTypeId() == null ? null : services.get(event.serviceTypeId())))
			.toList();
	}

	/**
	 * @param localStart the start in the studio's time zone
	 * @param service the event's service type, or {@code null} for an event without one
	 */
	record StartedEvent(ReportedEvent event, LocalDateTime localStart, EventOutcomes outcomes,
			@Nullable ServiceTypeSummary service) {

		/** The event's own title, or its service type's name. */
		String title() {
			String title = this.event.title();
			if (title != null && !title.isBlank()) {
				return title;
			}
			return this.service == null ? "" : this.service.name();
		}

	}

}
