package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Scheduling owns the dated session; booking adds its participant in the same transaction. */
public interface PersonalTrainingPlanning {

	List<Issue> preview(Plan plan, UUID staffId);

	UUID create(Plan plan, UUID staffId, UUID actorId, String participantName);

	/** Whether any supplied, already-authorized event overlaps this time and is not cancelled. */
	boolean overlaps(List<UUID> eventIds, Instant startsAt, Instant endsAt);

	record Plan(UUID serviceTypeId, UUID resourceId, Instant startsAt, Instant endsAt) {}

	record Issue(String type, boolean blocking, String eventTitle, Instant startsAt, Instant endsAt) {}
}
