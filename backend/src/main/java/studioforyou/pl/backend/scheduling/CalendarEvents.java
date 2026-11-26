package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Events as an external calendar shows them, e.g. in a private calendar feed. Callers pass only
 * events they are allowed to show, so neither visibility nor status is restricted. Blocks are
 * planning details, not appointments, and are never returned.
 */
public interface CalendarEvents {

	/** The given events, earliest first, whatever their status and visibility; unknown ids are left out. */
	List<CalendarEvent> findAllById(Collection<UUID> ids);

	/**
	 * Events the staff member runs or assists that end after {@code endingAfter}, earliest first,
	 * including cancelled ones.
	 */
	List<CalendarEvent> findAssignedTo(UUID staffId, Instant endingAfter);

	/**
	 * An event for a calendar.
	 *
	 * @param kind {@code CLASS}, {@code PERSONAL_TRAINING}, {@code WORKSHOP} or {@code CONSULTATION}
	 * @param status {@code SCHEDULED}, {@code COMPLETED} or {@code CANCELLED}
	 * @param title the event's own title, or {@code null} when it goes by its service type's name
	 * @param serviceTypeName the name of the event's service type, or {@code null} without one
	 * @param leads names of the staff members who run the event
	 * @param rooms names of the rooms the event takes place in
	 * @param version increases with every change of the event
	 */
	record CalendarEvent(UUID id, String kind, String status, @Nullable String title,
			@Nullable String serviceTypeName, Instant startsAt, Instant endsAt, List<String> leads,
			List<String> rooms, int version) {

		public boolean cancelled() {
			return "CANCELLED".equals(status);
		}

	}

}
