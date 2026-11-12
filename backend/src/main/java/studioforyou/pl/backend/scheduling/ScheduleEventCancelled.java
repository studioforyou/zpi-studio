package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that cancelled an event, so that the people it concerns can be
 * told once the cancellation is committed. The event carries everything the notice needs, because
 * the reservations of a cancelled class may already be cancelled when a listener runs.
 *
 * @param eventId the cancelled event
 * @param kind e.g. {@code CLASS} or {@code PERSONAL_TRAINING}
 * @param eventName the event's title, or its service type's name when it has none
 * @param startsAt the start of the cancelled event
 * @param endsAt the end of the cancelled event
 * @param rooms names of the rooms the event used
 * @param reason why the event was cancelled
 * @param customerIds participants to tell: those with an active reservation or on the waiting list
 * before the cancellation; empty when the participant cancelled it themselves
 * @param staffIds staff members who ran or assisted the event
 * @param cancelledBy the account that cancelled it, or {@code null} when the system did
 * @param participants for {@link Reason#TOO_FEW_PARTICIPANTS}, how many places were booked
 * @param minimumParticipants for {@link Reason#TOO_FEW_PARTICIPANTS}, how many were needed
 */
public record ScheduleEventCancelled(UUID eventId, String kind, @Nullable String eventName, Instant startsAt,
		Instant endsAt, List<String> rooms, Reason reason, Set<UUID> customerIds, Set<UUID> staffIds,
		@Nullable UUID cancelledBy, @Nullable Integer participants, @Nullable Integer minimumParticipants) {

	public ScheduleEventCancelled {
		rooms = List.copyOf(rooms);
		customerIds = Set.copyOf(customerIds);
		staffIds = Set.copyOf(staffIds);
	}

	/** Why an event was cancelled. */
	public enum Reason {

		/** The automatic rule: fewer participants booked than the minimum when it was checked. */
		TOO_FEW_PARTICIPANTS,

		/** The participant of a personal training cancelled it. */
		CANCELLED_BY_CLIENT,

		/** The studio staff cancelled it. */
		CANCELLED_BY_STUDIO

	}

}
