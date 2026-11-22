package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.scheduling.EventParticipantCounts;
import studioforyou.pl.backend.scheduling.ScheduleEventCancelled;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.Cancellation;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class StudioEventCancellation {

	private final ScheduleEventRepository events;
	private final ScheduleQueries queries;
	private final EventParticipantCounts participants;
	private final JdbcClient jdbc;
	private final AuditLog audit;
	private final ApplicationEventPublisher publisher;

	StudioEventCancellation(ScheduleEventRepository events, ScheduleQueries queries, EventParticipantCounts participants,
			JdbcClient jdbc, AuditLog audit, ApplicationEventPublisher publisher) {
		this.events = events; this.queries = queries; this.participants = participants;
		this.jdbc = jdbc; this.audit = audit; this.publisher = publisher;
	}

	void cancel(UUID id, Cancellation input, UUID actor) {
		var event = this.events.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found."));
		if (event.getVersion() != input.version()) throw new ApiException(ErrorCode.VERSION_CONFLICT, "Refresh the event.");
		if (event.getStatus() != EventStatus.SCHEDULED || !event.getStartsAt().isAfter(Instant.now())) {
			throw new ApiException(ErrorCode.EVENT_NOT_EDITABLE, "Only a future scheduled event can be cancelled.");
		}
		var details = this.queries.findEvent(id).orElseThrow();
		Set<UUID> customers = this.participants.participantsToTell(java.util.List.of(id)).getOrDefault(id, Set.of());
		event.cancel();
		this.events.flush();
		this.jdbc.sql("""
				UPDATE schedule_event SET cancel_reason = 'OTHER', cancel_note = :note,
				cancelled_by = :actor, cancelled_at = now() WHERE id = :id
				""").param("id", id).param("actor", actor).param("note", input.reason().strip()).update();
		this.jdbc.sql("""
				UPDATE substitution_request SET status = 'CANCELLED', decided_by = :actor, decided_at = now(),
				decision_note = :note, version = version + 1 WHERE event_id = :id AND status IN ('OPEN', 'CLAIMED')
				""").param("id", id).param("actor", actor).param("note", input.reason().strip()).update();
		this.audit.append(new AuditLog.Entry(actor, "scheduling", "schedule_event", id, "CANCELLED",
				Map.of("status", new AuditLog.Change("SCHEDULED", "CANCELLED")), input.reason().strip()));
		// Booking settles inside this transaction; notification listeners from #267 run after commit.
		this.publisher.publishEvent(new ScheduleEventCancelled(id, event.getKind().name(),
				details.title() == null ? details.serviceTypeName() : details.title(), event.getStartsAt(), event.getEndsAt(),
				details.resources().stream().map(ScheduleDtos.AssignedResourceResponse::name).toList(),
				ScheduleEventCancelled.Reason.CANCELLED_BY_STUDIO, customers,
				event.getStaff().stream().map(EventStaffAssignment::staffId).collect(Collectors.toSet()), actor, null, null));
	}
}
