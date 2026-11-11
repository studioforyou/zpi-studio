package studioforyou.pl.backend.scheduling;

import java.util.Set;
import java.util.UUID;

/**
 * Places already taken in events. Provided by the module that owns reservations, so that the
 * schedule can refuse a capacity below the number of participants.
 */
public interface EventParticipantCounts {

	/**
	 * The number of active reservations of the event. The schedule calls it while it holds the
	 * event's row lock, the same lock a booking takes, so the count cannot grow meanwhile.
	 */
	int activeParticipants(UUID eventId);

	/**
	 * The participants a change of the event concerns: those with an active reservation and those
	 * still on its waiting list.
	 */
	Set<UUID> participantsToTell(UUID eventId);

}
