package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Published inside the transaction that saved a change of the time, the rooms or the staff of
 * events, with the change notices it left for the people concerned (the same notices the app
 * shows on the schedule). Each notice concerns one participant or one staff member.
 *
 * @param notices the notices of the change, at least one
 */
public record ScheduleChangesRecorded(List<Notice> notices) {

	public ScheduleChangesRecorded {
		notices = List.copyOf(notices);
	}

	/**
	 * One change of an event told to one participant ({@code customerId}) or one staff member
	 * ({@code staffId}), with the values before and after the change.
	 *
	 * @param noticeId the {@code schedule_change_notice.id}
	 * @param eventName the event's title, or its service type's name when it has none
	 * @param previousLeads lead trainers before the change
	 * @param leads lead trainers after the change
	 */
	public record Notice(UUID noticeId, UUID eventId, Kind kind, @Nullable UUID customerId, @Nullable UUID staffId,
			@Nullable String eventName, Instant previousStartsAt, Instant previousEndsAt, Instant startsAt,
			Instant endsAt, List<String> previousRooms, List<String> rooms, List<String> previousLeads,
			List<String> leads) {

		public Notice {
			previousRooms = List.copyOf(previousRooms);
			rooms = List.copyOf(rooms);
			previousLeads = List.copyOf(previousLeads);
			leads = List.copyOf(leads);
		}

	}

	/** What the change means for the person told. */
	public enum Kind {

		/** The event moved or got other rooms or staff. */
		CHANGED,

		/** The staff member now works at the event. */
		ASSIGNED,

		/** The staff member no longer works at the event. */
		UNASSIGNED

	}

}
