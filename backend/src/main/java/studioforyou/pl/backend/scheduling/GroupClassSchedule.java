package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public group-class occurrences. Participation and available places belong to booking. */
public interface GroupClassSchedule {

	List<Occurrence> list(Instant from, Instant to);

	Optional<Occurrence> find(UUID id);

	/** Holds the same occurrence row lock as schedule edits until the caller's transaction ends. */
	Occurrence lock(UUID id);

	record Occurrence(UUID id, String title, UUID serviceTypeId, String serviceTypeName, String calendarColor, UUID trainingGroupId,
			Instant startsAt, Instant endsAt, String status, Integer capacity, List<Instructor> staff,
			List<Room> resources, int version) {
	}

	record Instructor(UUID staffId, String displayName, String role) {
	}

	record Room(UUID resourceId, String name) {
	}

}
