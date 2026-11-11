package studioforyou.pl.backend.scheduling;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Places already taken in events. Provided by the module that owns reservations, so that the
 * schedule can refuse a capacity below the number of participants.
 */
public interface EventParticipantCounts {

	/**
	 * The places taken in the event: active reservations and places held for waiting-list offers
	 * that have not run out. The schedule calls it while it holds the event's row lock, the same
	 * lock a booking takes, so the count cannot grow meanwhile.
	 */
	int activeParticipants(UUID eventId);

	/**
	 * Per event, the participants a change of it concerns: those with an active reservation and
	 * those still on its waiting list. Events without such participants are left out.
	 */
	Map<UUID, Set<UUID>> participantsToTell(Collection<UUID> eventIds);

}
