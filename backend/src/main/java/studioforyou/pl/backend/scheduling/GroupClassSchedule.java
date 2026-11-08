package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Group-class occurrences. The customer offer contains only public classes; the studio staff also
 * book participants on private ones, which their participants then see among their reservations.
 * Participation and available places belong to booking.
 */
public interface GroupClassSchedule {

	/** Public group classes overlapping {@code [from, to)}: the customer offer. */
	List<Occurrence> list(Instant from, Instant to);

	/** Public and private group classes overlapping {@code [from, to)}. */
	List<Occurrence> listIncludingPrivate(Instant from, Instant to);

	/** A public group class. */
	Optional<Occurrence> find(UUID id);

	/** A public or private group class. */
	Optional<Occurrence> findIncludingPrivate(UUID id);

	/**
	 * The given events that start in {@code [from, to)}, latest first, for a participant's history.
	 * The caller passes only events it holds participation for, so neither kind nor visibility is
	 * restricted.
	 */
	List<Occurrence> findStartingBetween(Collection<UUID> ids, Instant from, Instant to);

	/**
	 * A public group class, holding the same occurrence row lock as schedule edits until the caller's
	 * transaction ends.
	 */
	Occurrence lock(UUID id);

	/** Like {@link #lock(UUID)}, for a public or private group class. */
	Occurrence lockIncludingPrivate(UUID id);

	record Occurrence(UUID id, String title, UUID serviceTypeId, String serviceTypeName, String calendarColor, UUID trainingGroupId,
			Instant startsAt, Instant endsAt, String status, Integer capacity, List<Instructor> staff,
			List<Room> resources, int version) {
	}

	record Instructor(UUID staffId, String displayName, String role) {
	}

	record Room(UUID resourceId, String name) {
	}

}
