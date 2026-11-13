package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Group-class occurrences. The customer offer contains only public classes; the studio staff also
 * book participants on private ones, which their participants then see among their reservations.
 * Participation and available places belong to booking.
 */
public interface GroupClassSchedule {

	/**
	 * Public group classes overlapping {@code [from, to)}: the customer offer. It also holds the classes moved
	 * this once away from a usual time in the window, so participants can see where they went.
	 */
	List<Occurrence> list(Instant from, Instant to);

	/** Public and private group classes overlapping {@code [from, to)}, with the moved ones like {@link #list}. */
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

	/** Caller-authorized occurrences, including private and cancelled ones, without a date restriction. */
	List<Occurrence> findAllById(Collection<UUID> ids);

	/**
	 * A public group class, holding the same occurrence row lock as schedule edits until the caller's
	 * transaction ends.
	 */
	Occurrence lock(UUID id);

	/** Like {@link #lock(UUID)}, for a public or private group class. */
	Occurrence lockIncludingPrivate(UUID id);

	/**
	 * A group class. {@code seriesChange} is how a one-off change makes it differ from its series, or null.
	 */
	record Occurrence(UUID id, String title, UUID serviceTypeId, String serviceTypeName, String calendarColor, UUID trainingGroupId,
			Instant startsAt, Instant endsAt, String status, Integer capacity, List<Instructor> staff,
			List<Room> resources, int version, @Nullable SeriesChange seriesChange) {

		/** An occurrence as its series' rule has it. */
		public Occurrence(UUID id, String title, UUID serviceTypeId, String serviceTypeName, String calendarColor,
				UUID trainingGroupId, Instant startsAt, Instant endsAt, String status, Integer capacity,
				List<Instructor> staff, List<Room> resources, int version) {
			this(id, title, serviceTypeId, serviceTypeName, calendarColor, trainingGroupId, startsAt, endsAt, status,
					capacity, staff, resources, version, null);
		}

	}

	record Instructor(UUID staffId, String displayName, String role) {
	}

	record Room(UUID resourceId, String name) {
	}

}
