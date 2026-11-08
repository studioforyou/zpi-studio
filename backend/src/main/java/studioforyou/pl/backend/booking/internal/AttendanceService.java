package studioforyou.pl.backend.booking.internal;

import java.text.Collator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.audit.AuditLog;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.AttendanceChangeResponse;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.AttendanceMarkRequest;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.AttendanceParticipantResponse;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.AttendanceRecordResponse;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.CorrectAttendanceRequest;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.EventAttendanceResponse;
import studioforyou.pl.backend.booking.internal.AttendanceDtos.OwnEventAttendanceResponse;
import studioforyou.pl.backend.booking.internal.ReservationStore.AttendanceRow;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.scheduling.ScheduledEvents.AttendedEvent;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * Attendance of the participants of an event, for any event kind with participants, group classes
 * and personal sessions alike. The event's trainers record it from the start until
 * {@link #TRAINER_MARKING_AFTER_END} after the end and mark the event as held, which records every
 * participant still unmarked as present; administrators correct it at any time after the start.
 * <p>
 * The first recorded attendance settles the participant's pass entry as used, whether present or
 * absent, so later changes between the two do not affect the pass. Every change is appended to the
 * audit log with who made it and why, so a correction never loses the history. Lock order is event,
 * reservations, then passes in id order, as in booking.
 */
@Service
@Transactional
class AttendanceService {

	/** How long after the event's end its trainers can still record attendance. */
	static final Duration TRAINER_MARKING_AFTER_END = Duration.ofHours(12);

	static final String UNMARKED = "UNMARKED";

	private static final String MODULE = "booking";

	private static final String ENTITY_TYPE = "reservation";

	private static final String ACTION_PREFIX = "ATTENDANCE_";

	private static final Comparator<ProfileDetails> BY_NAME;

	static {
		Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
		BY_NAME = Comparator.comparing(ProfileDetails::lastName, collator)
			.thenComparing(ProfileDetails::firstName, collator);
	}

	private final ReservationStore reservations;

	private final ScheduledEvents events;

	private final StaffProfiles staffProfiles;

	private final CustomerProfiles customers;

	private final BookingEntitlements entitlements;

	private final AuditLog auditLog;

	private final Clock clock = Clock.systemUTC();

	AttendanceService(ReservationStore reservations, ScheduledEvents events, StaffProfiles staffProfiles,
			CustomerProfiles customers, BookingEntitlements entitlements, AuditLog auditLog) {
		this.reservations = reservations;
		this.events = events;
		this.staffProfiles = staffProfiles;
		this.customers = customers;
		this.entitlements = entitlements;
		this.auditLog = auditLog;
	}

	@Transactional(readOnly = true)
	OwnEventAttendanceResponse ownSheet(UUID accountId, UUID eventId) {
		requireAssigned(accountId, eventId);
		AttendedEvent event = this.events.findAttended(eventId).orElseThrow(AttendanceService::eventNotFound);
		return ownSheet(event, this.clock.instant());
	}

	/** Records the trainer's marks all together; a mark equal to the current attendance changes nothing. */
	OwnEventAttendanceResponse mark(UUID accountId, UUID eventId, List<AttendanceMarkRequest> marks) {
		requireAssigned(accountId, eventId);
		Map<UUID, String> wanted = new HashMap<>();
		for (AttendanceMarkRequest mark : marks) {
			if (wanted.put(mark.reservationId(), mark.attendance().name()) != null) {
				throw ApiException.invalidField("marks", FieldErrorCode.INVALID_VALUE,
						"Each reservation can be marked only once in a request.");
			}
		}
		AttendedEvent event = this.events.lockAttended(eventId).orElseThrow(AttendanceService::eventNotFound);
		Instant now = this.clock.instant();
		requireTrainerMarking(event, now);
		Map<UUID, AttendanceRow> active = this.reservations.lockAttendance(eventId).stream()
			.collect(Collectors.toMap(AttendanceRow::id, Function.identity()));
		Set<UUID> missing = new HashSet<>(wanted.keySet());
		missing.removeAll(active.keySet());
		if (!missing.isEmpty()) {
			throw this.reservations.inactiveAmong(eventId, missing).isEmpty()
					? new ApiException(ErrorCode.NOT_FOUND, "Reservation not found.")
					: new ApiException(ErrorCode.RESERVATION_NOT_ACTIVE, "A cancelled reservation has no attendance.");
		}
		inPassOrder(wanted.keySet().stream().map(active::get))
			.forEach(row -> change(row, wanted.get(row.id()), accountId, "MARKED", null, now));
		return ownSheet(event, now);
	}

	/**
	 * Marks the event as held: participants still unmarked are recorded as present, a personal
	 * session also gets the trainer's confirmation, and the event becomes {@code COMPLETED}.
	 * Repeating it changes nothing more.
	 */
	OwnEventAttendanceResponse complete(UUID accountId, UUID eventId) {
		requireAssigned(accountId, eventId);
		AttendedEvent event = this.events.lockAttended(eventId).orElseThrow(AttendanceService::eventNotFound);
		Instant now = this.clock.instant();
		requireTrainerMarking(event, now);
		inPassOrder(this.reservations.lockAttendance(eventId).stream().filter(row -> UNMARKED.equals(row.attendance())))
			.forEach(row -> change(row, AttendanceDtos.RecordedAttendance.PRESENT.name(), accountId, "DEFAULTED",
					null, now));
		if ("PERSONAL_TRAINING".equals(event.kind())) {
			this.reservations.confirmByTrainer(eventId, now);
		}
		this.events.markCompleted(eventId, accountId);
		return ownSheet(this.events.findAttended(eventId).orElseThrow(AttendanceService::eventNotFound), now);
	}

	@Transactional(readOnly = true)
	EventAttendanceResponse sheet(UUID eventId) {
		AttendedEvent event = this.events.findAttended(eventId).orElseThrow(AttendanceService::eventNotFound);
		Instant now = this.clock.instant();
		List<AttendanceRow> rows = this.reservations.attendance(eventId);
		Map<UUID, List<AuditLog.Recorded>> history = history(rows.stream().map(AttendanceRow::id).toList());
		Map<UUID, String> names = actorNames(Stream.concat(rows.stream().map(AttendanceRow::attendanceMarkedBy),
				history.values().stream().flatMap(List::stream).map(AuditLog.Recorded::actorAccountId)));
		List<AttendanceRecordResponse> participants = byName(rows).stream()
			.map(entry -> record(entry.getKey(), entry.getValue(), history.getOrDefault(entry.getKey().id(), List.of()),
					names))
			.toList();
		return new EventAttendanceResponse(event.id(), event.kind(), event.status(), event.startsAt(), event.endsAt(),
				correctable(event, now), trainerMarkingClosesAt(event), trainerMarkingOpen(event, now), participants);
	}

	/** An administrator's correction, kept in the history with its optional reason. */
	AttendanceRecordResponse correct(UUID actorId, UUID eventId, UUID reservationId, CorrectAttendanceRequest request) {
		AttendedEvent event = this.events.lockAttended(eventId).orElseThrow(AttendanceService::eventNotFound);
		Instant now = this.clock.instant();
		if (event.cancelled()) {
			throw eventCancelled();
		}
		if (now.isBefore(event.startsAt())) {
			throw notOpen();
		}
		AttendanceRow row = this.reservations.lockAttendance(eventId, reservationId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Reservation not found."));
		if (!"ACTIVE".equals(row.status())) {
			throw new ApiException(ErrorCode.RESERVATION_NOT_ACTIVE, "A cancelled reservation has no attendance.");
		}
		if (row.version() != request.version()) {
			throw new ApiException(ErrorCode.VERSION_CONFLICT,
					"The participant's attendance has changed. Refresh it before correcting.");
		}
		String reason = (request.reason() == null || request.reason().isBlank()) ? null : request.reason().strip();
		AttendanceRow corrected = change(row, request.attendance().name(), actorId, "CORRECTED", reason, now);
		ProfileDetails profile = this.customers.findById(corrected.customerId())
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
		List<AuditLog.Recorded> history = history(List.of(reservationId)).getOrDefault(reservationId, List.of());
		Map<UUID, String> names = actorNames(Stream.concat(Stream.of(corrected.attendanceMarkedBy()),
				history.stream().map(AuditLog.Recorded::actorAccountId)));
		return record(corrected, profile, history, names);
	}

	/** The time window of the event's trainers: from the start until 12 hours after the end. */
	static boolean trainerMarkingOpen(AttendedEvent event, Instant now) {
		return !event.cancelled() && !now.isBefore(event.startsAt()) && !now.isAfter(trainerMarkingClosesAt(event));
	}

	static Instant trainerMarkingClosesAt(AttendedEvent event) {
		return event.endsAt().plus(TRAINER_MARKING_AFTER_END);
	}

	static boolean correctable(AttendedEvent event, Instant now) {
		return !event.cancelled() && !now.isBefore(event.startsAt());
	}

	private static void requireTrainerMarking(AttendedEvent event, Instant now) {
		if (event.cancelled()) {
			throw eventCancelled();
		}
		if (now.isBefore(event.startsAt())) {
			throw notOpen();
		}
		if (now.isAfter(trainerMarkingClosesAt(event))) {
			throw new ApiException(ErrorCode.ATTENDANCE_CLOSED,
					"Attendance can be recorded until 12 hours after the event. An administrator can correct it.");
		}
	}

	/** Records a new attendance; the first recorded one settles the pass entry as used. */
	private AttendanceRow change(AttendanceRow row, String attendance, UUID actorId, String kind, String reason,
			Instant now) {
		if (attendance.equals(row.attendance())) {
			return row;
		}
		if (row.customerPassId() != null) {
			this.entitlements.consume(row.customerId(), row.customerPassId(), row.id(), actorId);
		}
		AttendanceRow changed = this.reservations.markAttendance(row.id(), attendance, actorId, now);
		this.auditLog.append(new AuditLog.Entry(actorId, MODULE, ENTITY_TYPE, row.id(), ACTION_PREFIX + kind,
				Map.of("attendance", new AuditLog.Change(row.attendance(), attendance)), reason));
		return changed;
	}

	/** Passes are locked in ascending id order, so two transactions never wait for each other's passes. */
	private static Stream<AttendanceRow> inPassOrder(Stream<AttendanceRow> rows) {
		return rows.sorted(Comparator.comparing(AttendanceRow::customerPassId, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(AttendanceRow::id));
	}

	private OwnEventAttendanceResponse ownSheet(AttendedEvent event, Instant now) {
		List<AttendanceParticipantResponse> participants = byName(this.reservations.attendance(event.id())).stream()
			.map(entry -> participant(entry.getKey(), entry.getValue()))
			.toList();
		return new OwnEventAttendanceResponse(event.id(), event.kind(), event.status(), event.startsAt(),
				trainerMarkingClosesAt(event), trainerMarkingOpen(event, now), participants);
	}

	/** Reservations with their participants, by last and first name, then booking time. */
	private List<Map.Entry<AttendanceRow, ProfileDetails>> byName(List<AttendanceRow> rows) {
		Map<UUID, ProfileDetails> profiles = this.customers
			.findAllById(rows.stream().map(AttendanceRow::customerId).toList())
			.stream()
			.collect(Collectors.toMap(ProfileDetails::id, Function.identity()));
		return rows.stream()
			.filter(row -> profiles.containsKey(row.customerId()))
			.map(row -> Map.entry(row, profiles.get(row.customerId())))
			.sorted(Map.Entry.<AttendanceRow, ProfileDetails>comparingByValue(BY_NAME)
				.thenComparing(entry -> entry.getKey().bookedAt()))
			.toList();
	}

	private Map<UUID, List<AuditLog.Recorded>> history(Collection<UUID> reservationIds) {
		return this.auditLog.history(MODULE, ENTITY_TYPE, reservationIds).stream()
			.filter(entry -> entry.action().startsWith(ACTION_PREFIX))
			.collect(Collectors.groupingBy(AuditLog.Recorded::entityId));
	}

	/** Names of the staff accounts that recorded attendance; accounts without a staff profile are left out. */
	private Map<UUID, String> actorNames(Stream<UUID> accountIds) {
		Map<UUID, String> names = new HashMap<>();
		accountIds.filter(Objects::nonNull).distinct().forEach(accountId -> this.staffProfiles.findByAccount(accountId)
			.ifPresent(staff -> names.put(accountId, staff.displayName())));
		return names;
	}

	private static AttendanceParticipantResponse participant(AttendanceRow row, ProfileDetails profile) {
		return new AttendanceParticipantResponse(row.id(), profile.id(), profile.kind(), profile.firstName(),
				profile.lastName(), profile.displayName(), profile.birthDate(), row.bookedAt(), row.attendance(),
				row.attendanceMarkedAt());
	}

	private static AttendanceRecordResponse record(AttendanceRow row, ProfileDetails profile,
			List<AuditLog.Recorded> history, Map<UUID, String> names) {
		return new AttendanceRecordResponse(row.id(), profile.id(), profile.kind(), profile.firstName(),
				profile.lastName(), profile.displayName(), row.bookedAt(), row.attendance(), row.attendanceMarkedAt(),
				Optional.ofNullable(row.attendanceMarkedBy()).map(names::get).orElse(null), row.version(),
				history.stream().map(entry -> historyEntry(entry, names)).toList());
	}

	private static AttendanceChangeResponse historyEntry(AuditLog.Recorded entry, Map<UUID, String> names) {
		AuditLog.Change attendance = entry.changes().get("attendance");
		return new AttendanceChangeResponse(entry.occurredAt(), entry.action().substring(ACTION_PREFIX.length()),
				attendance == null ? null : Objects.toString(attendance.oldValue(), null),
				attendance == null ? null : Objects.toString(attendance.newValue(), null),
				entry.actorAccountId() == null ? null : names.get(entry.actorAccountId()), entry.reason());
	}

	private void requireAssigned(UUID accountId, UUID eventId) {
		UUID staffId = this.staffProfiles.getByAccount(accountId).id();
		if (!this.events.isAssigned(eventId, staffId)) {
			// The same answer as for a missing event, so other trainers' events stay hidden.
			throw eventNotFound();
		}
	}

	private static ApiException eventNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Schedule event not found.");
	}

	private static ApiException eventCancelled() {
		return new ApiException(ErrorCode.EVENT_CANCELLED, "A cancelled event has no attendance.");
	}

	private static ApiException notOpen() {
		return new ApiException(ErrorCode.ATTENDANCE_NOT_OPEN, "Attendance can be recorded once the event has started.");
	}

}
