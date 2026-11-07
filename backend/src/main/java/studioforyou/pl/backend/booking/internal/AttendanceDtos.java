package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import studioforyou.pl.backend.customer.CustomerKind;

/**
 * Attendance of an event's participants: the trainer's sheet and the administrator's view with the
 * history of every change. Only participants with an active reservation are listed; a cancelled
 * reservation has no attendance.
 */
final class AttendanceDtos {

	private static final String ATTENDANCE = "UNMARKED (not recorded yet), PRESENT or ABSENT (did not come "
			+ "without cancelling). Both PRESENT and ABSENT use the pass entry.";

	private AttendanceDtos() {
	}

	/** Attendance a trainer or an administrator records; it cannot be cleared again. */
	enum RecordedAttendance {

		PRESENT, ABSENT

	}

	@Schema(name = "OwnEventAttendance", description = "The signed-in trainer's attendance sheet of one of their events.")
	record OwnEventAttendanceResponse(UUID eventId,
			@Schema(example = "CLASS") String eventKind,
			@Schema(description = "SCHEDULED, COMPLETED (marked as held) or CANCELLED.") String eventStatus,
			@Schema(description = "From when the trainer can record attendance: the event's start.") Instant markingOpensAt,
			@Schema(description = "Until when the trainer can record and change attendance: 12 hours after the event "
					+ "ends. Later only an administrator corrects it.") Instant markingClosesAt,
			@Schema(description = "Whether the trainer can record attendance and mark the event as held now.")
			boolean markingOpen,
			List<AttendanceParticipantResponse> participants) {
	}

	@Schema(name = "AttendanceParticipant", description = "A participant with an active reservation, without contact details.")
	record AttendanceParticipantResponse(UUID reservationId, UUID customerId, CustomerKind kind,
			@Schema(example = "Katarzyna") String firstName, @Schema(example = "Zielińska") String lastName,
			@Schema(example = "Katarzyna Zielińska") String displayName,
			@Schema(description = "The date of birth, or null if unknown.") LocalDate birthDate, Instant bookedAt,
			@Schema(description = ATTENDANCE, example = "PRESENT") String attendance,
			@Schema(description = "When the current attendance was recorded; null while UNMARKED.") Instant attendanceMarkedAt) {
	}

	@Schema(name = "MarkAttendanceRequest")
	record MarkAttendanceRequest(
			@Schema(description = "The participants' attendance, at most one entry per reservation. Participants left "
					+ "out keep theirs.")
			@NotEmpty @Size(max = 500) List<@Valid @NotNull AttendanceMarkRequest> marks) {
	}

	@Schema(name = "AttendanceMark")
	record AttendanceMarkRequest(@NotNull UUID reservationId, @NotNull RecordedAttendance attendance) {
	}

	@Schema(name = "EventAttendance", description = "Attendance of an event's participants for administrators.")
	record EventAttendanceResponse(UUID eventId, @Schema(example = "CLASS") String eventKind,
			@Schema(description = "SCHEDULED, COMPLETED (marked as held) or CANCELLED.") String eventStatus,
			Instant startsAt, Instant endsAt,
			@Schema(description = "Whether an administrator can correct attendance: the event has started and was not "
					+ "cancelled.") boolean correctable,
			@Schema(description = "Until when the event's trainers can still record attendance themselves.")
			Instant trainerMarkingClosesAt,
			@Schema(description = "Whether the event's trainers can record attendance themselves now.")
			boolean trainerMarkingOpen,
			List<AttendanceRecordResponse> participants) {
	}

	@Schema(name = "AttendanceRecord", description = "A participant's attendance with the history of its changes.")
	record AttendanceRecordResponse(UUID reservationId, UUID customerId, CustomerKind kind,
			@Schema(example = "Katarzyna") String firstName, @Schema(example = "Zielińska") String lastName,
			@Schema(example = "Katarzyna Zielińska") String displayName, Instant bookedAt,
			@Schema(description = ATTENDANCE, example = "PRESENT") String attendance,
			@Schema(description = "When the current attendance was recorded; null while UNMARKED.") Instant attendanceMarkedAt,
			@Schema(description = "Who recorded the current attendance, or null when unknown or UNMARKED.",
					example = "Anna Kowalska") String attendanceMarkedBy,
			@Schema(description = "The reservation's version, sent back with a correction.") int version,
			@Schema(description = "Every change of the attendance, oldest first.") List<AttendanceChangeResponse> history) {
	}

	@Schema(name = "AttendanceChange")
	record AttendanceChangeResponse(Instant changedAt,
			@Schema(description = "MARKED (by the event's trainer), DEFAULTED (PRESENT when the trainer marked the "
					+ "event as held without recording it) or CORRECTED (by an administrator).", example = "CORRECTED")
			String kind,
			@Schema(example = "ABSENT") String previousAttendance, @Schema(example = "PRESENT") String newAttendance,
			@Schema(description = "Who made the change, or null when unknown.", example = "Beata Wrona") String changedBy,
			@Schema(description = "Why, if given.", example = "The participant came late") String reason) {
	}

	@Schema(name = "CorrectAttendanceRequest")
	record CorrectAttendanceRequest(@NotNull RecordedAttendance attendance,
			@Schema(description = "Optional reason, kept in the history.", example = "The participant came late")
			@Size(max = 500) String reason,
			@Schema(description = "The reservation's current version; a stale value returns 409 VERSION_CONFLICT.")
			@NotNull @Min(0) Integer version) {
	}

}
