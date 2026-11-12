package studioforyou.pl.backend.scheduling.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.scheduling.EventParticipantCounts;
import studioforyou.pl.backend.scheduling.ScheduleChangesRecorded;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedResourceResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.AssignedStaffResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleDtos.ScheduleEventResponse;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tells everyone a change of an event concerns about it: each saved change of the time, the rooms
 * or the staff leaves one notice for each participant with an active reservation or on the waiting
 * list, and for each staff member before and after the change. A staff member who joins the event
 * gets ASSIGNED, one who leaves it UNASSIGNED, the rest CHANGED, each with the values before and
 * after. A later change is a new notice, even of the same event. A change of several occurrences
 * at once tells each person once, about the earliest occurrence that concerns them. Changes of the
 * title or the capacity only, and changes of blocks, tell nobody. The notices of a change are also
 * published as {@link ScheduleChangesRecorded}, so that the people get a push notification too.
 */
@Component
class ScheduleChangeNotices {

	private final JdbcClient jdbc;

	private final ScheduleQueries queries;

	private final EventParticipantCounts participants;

	private final ApplicationEventPublisher publisher;

	ScheduleChangeNotices(JdbcClient jdbc, ScheduleQueries queries, EventParticipantCounts participants,
			ApplicationEventPublisher publisher) {
		this.jdbc = jdbc;
		this.queries = queries;
		this.participants = participants;
		this.publisher = publisher;
	}

	/** What the people of an event see of it: its time, rooms, leads and staff. */
	record Snapshot(UUID eventId, EventKind kind, @Nullable String name, Instant startsAt, Instant endsAt,
			List<String> rooms, List<String> leads, Set<UUID> staffIds) {

		boolean sameFor(Snapshot other) {
			return this.startsAt.equals(other.startsAt) && this.endsAt.equals(other.endsAt)
					&& this.rooms.equals(other.rooms) && this.leads.equals(other.leads)
					&& this.staffIds.equals(other.staffIds);
		}

		static Snapshot of(ScheduleEventResponse event) {
			Set<UUID> staff = new LinkedHashSet<>();
			List<String> leads = new ArrayList<>();
			for (AssignedStaffResponse member : event.staff()) {
				staff.add(member.staffId());
				if (member.role() == StaffRole.LEAD && member.displayName() != null) {
					leads.add(member.displayName());
				}
			}
			List<String> rooms = event.resources().stream().map(AssignedResourceResponse::name).toList();
			String name = (event.title() != null && !event.title().isBlank()) ? event.title()
					: event.serviceTypeName();
			return new Snapshot(event.id(), event.kind(), name, event.startsAt(), event.endsAt(), rooms,
					List.copyOf(leads), staff);
		}

	}

	/** The events as they are now, read in one go; take them before a change. */
	Map<UUID, Snapshot> snapshots(Collection<UUID> eventIds) {
		return this.queries.findEventsById(eventIds)
			.stream()
			.map(Snapshot::of)
			.collect(Collectors.toMap(Snapshot::eventId, Function.identity()));
	}

	/**
	 * Leaves the notices of a change of the given events, in the change's transaction, once the
	 * change is flushed, and publishes them as {@link ScheduleChangesRecorded}. {@code before}
	 * holds the events as {@link #snapshots} read them before.
	 */
	void record(Map<UUID, Snapshot> before) {
		Map<UUID, Snapshot> after = snapshots(before.keySet());
		List<Snapshot> changed = before.values()
			.stream()
			.filter((previous) -> after.containsKey(previous.eventId()))
			.filter((previous) -> previous.kind() != EventKind.BLOCK
					&& !previous.sameFor(after.get(previous.eventId())))
			.sorted(Comparator.comparing(Snapshot::startsAt).thenComparing(Snapshot::eventId))
			.toList();
		if (changed.isEmpty()) {
			return;
		}
		Map<UUID, Set<UUID>> customersByEvent = this.participants
			.participantsToTell(changed.stream().map(Snapshot::eventId).toList());
		Set<UUID> toldCustomers = new HashSet<>();
		Set<UUID> toldStaff = new HashSet<>();
		List<ScheduleChangesRecorded.Notice> recorded = new ArrayList<>();
		for (Snapshot previous : changed) {
			Snapshot current = after.get(previous.eventId());
			for (UUID customer : customersByEvent.getOrDefault(previous.eventId(), Set.of())) {
				if (toldCustomers.add(customer)) {
					recorded.add(insert(previous, current, customer, null, NoticeKind.CHANGED));
				}
			}
			Set<UUID> staff = new LinkedHashSet<>(previous.staffIds());
			staff.addAll(current.staffIds());
			for (UUID member : staff) {
				if (toldStaff.add(member)) {
					NoticeKind kind = !previous.staffIds().contains(member) ? NoticeKind.ASSIGNED
							: !current.staffIds().contains(member) ? NoticeKind.UNASSIGNED : NoticeKind.CHANGED;
					recorded.add(insert(previous, current, null, member, kind));
				}
			}
		}
		if (!recorded.isEmpty()) {
			this.publisher.publishEvent(new ScheduleChangesRecorded(recorded));
		}
	}

	private ScheduleChangesRecorded.Notice insert(Snapshot before, Snapshot after, @Nullable UUID customerId,
			@Nullable UUID staffId, NoticeKind kind) {
		UUID id = this.jdbc.sql("""
				INSERT INTO schedule_change_notice (event_id, customer_id, staff_id, kind, event_name,
				    previous_starts_at, previous_ends_at, starts_at, ends_at, previous_rooms, rooms,
				    previous_leads, leads)
				VALUES (:event, :customer, :staff, :kind, :name, :previousStart, :previousEnd, :start, :end,
				    :previousRooms, :rooms, :previousLeads, :leads)
				RETURNING id
				""")
			.param("event", after.eventId())
			.param("customer", customerId)
			.param("staff", staffId)
			.param("kind", kind.name())
			.param("name", after.name())
			.param("previousStart", Timestamp.from(before.startsAt()))
			.param("previousEnd", Timestamp.from(before.endsAt()))
			.param("start", Timestamp.from(after.startsAt()))
			.param("end", Timestamp.from(after.endsAt()))
			.param("previousRooms", before.rooms().toArray(String[]::new))
			.param("rooms", after.rooms().toArray(String[]::new))
			.param("previousLeads", before.leads().toArray(String[]::new))
			.param("leads", after.leads().toArray(String[]::new))
			.query(UUID.class)
			.single();
		return new ScheduleChangesRecorded.Notice(id, after.eventId(), ScheduleChangesRecorded.Kind.valueOf(kind.name()),
				customerId, staffId, after.name(), before.startsAt(), before.endsAt(), after.startsAt(), after.endsAt(),
				before.rooms(), after.rooms(), before.leads(), after.leads());
	}

	/** Whose notices: a participant or a staff member. */
	record Owner(@Nullable UUID customerId, @Nullable UUID staffId) {

		static Owner customer(UUID customerId) {
			return new Owner(customerId, null);
		}

		static Owner staff(UUID staffId) {
			return new Owner(null, staffId);
		}

		private String column() {
			return (this.customerId != null) ? "customer_id" : "staff_id";
		}

		private UUID id() {
			return Objects.requireNonNull((this.customerId != null) ? this.customerId : this.staffId);
		}

	}

	/** The owner's notices not closed yet of events that have not ended, newest first. */
	List<NoticeResponse> open(Owner owner, Instant now) {
		return this.jdbc.sql("SELECT * FROM schedule_change_notice WHERE " + owner.column() + " = :owner "
				+ "AND dismissed_at IS NULL AND ends_at > :now ORDER BY created_at DESC, id")
			.params(Map.of("owner", owner.id(), "now", Timestamp.from(now)))
			.query(NOTICE)
			.list();
	}

	/** Closes one notice; false when the owner has no such open notice. */
	boolean dismiss(Owner owner, UUID noticeId, Instant now) {
		return this.jdbc.sql("UPDATE schedule_change_notice SET dismissed_at = :now "
				+ "WHERE id = :id AND " + owner.column() + " = :owner AND dismissed_at IS NULL")
			.params(Map.of("id", noticeId, "owner", owner.id(), "now", Timestamp.from(now)))
			.update() == 1;
	}

	/** Closes all open notices of the owner; returns how many. */
	int dismissAll(Owner owner, Instant now) {
		return this.jdbc.sql("UPDATE schedule_change_notice SET dismissed_at = :now "
				+ "WHERE " + owner.column() + " = :owner AND dismissed_at IS NULL")
			.params(Map.of("owner", owner.id(), "now", Timestamp.from(now)))
			.update();
	}

	enum NoticeKind {

		CHANGED, ASSIGNED, UNASSIGNED

	}

	@Schema(name = "ScheduleChangeNotice", description = "One saved change of an event told to the person it "
			+ "concerns, with the values before and after. Each later change is a new notice.")
	record NoticeResponse(UUID id, UUID eventId,
			@Schema(description = "CHANGED: the event moved or got other rooms or staff; ASSIGNED: the trainer "
					+ "now works at it; UNASSIGNED: the trainer no longer does.") NoticeKind kind,
			@Schema(example = "Pole dance początkujący") String eventName, Instant previousStartsAt,
			Instant previousEndsAt, Instant startsAt, Instant endsAt, List<String> previousRooms, List<String> rooms,
			@Schema(description = "Lead trainers before the change.") List<String> previousLeads,
			@Schema(description = "Lead trainers after the change.") List<String> leads, Instant createdAt) {
	}

	private static final RowMapper<NoticeResponse> NOTICE = (rs, rowNum) -> new NoticeResponse(
			rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
			NoticeKind.valueOf(rs.getString("kind")), rs.getString("event_name"), instant(rs, "previous_starts_at"),
			instant(rs, "previous_ends_at"), instant(rs, "starts_at"), instant(rs, "ends_at"),
			strings(rs, "previous_rooms"), strings(rs, "rooms"), strings(rs, "previous_leads"), strings(rs, "leads"),
			instant(rs, "created_at"));

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return rs.getTimestamp(column).toInstant();
	}

	private static List<String> strings(ResultSet rs, String column) throws SQLException {
		Array array = rs.getArray(column);
		return (array == null) ? List.of() : Arrays.asList((String[]) array.getArray());
	}

}
