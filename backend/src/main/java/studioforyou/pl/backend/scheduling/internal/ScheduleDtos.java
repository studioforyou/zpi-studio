package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of the schedule endpoints: events, recurring series and the
 * staff and rooms assigned to them.
 */
final class ScheduleDtos {

	static final int MAX_TITLE_LENGTH = 120;

	static final int MAX_DURATION_MINUTES = 720;

	static final int MAX_INTERVAL_WEEKS = 8;

	private ScheduleDtos() {
	}

	@Schema(name = "ScheduleStaffAssignment", description = "A staff member assigned to an event.")
	record StaffAssignmentRequest(@NotNull UUID staffId,
			@Schema(description = "LEAD when missing.") StaffRole role) {

		EventStaffAssignment toAssignment() {
			return new EventStaffAssignment(this.staffId, (this.role != null) ? this.role : StaffRole.LEAD);
		}

	}

	@Schema(name = "ScheduleResourceAssignment", description = "A room used by an event.")
	record ResourceAssignmentRequest(@NotNull UUID resourceId,
			@Schema(description = "true: no other event may use the room at the same time; false: it can be "
					+ "shared with other events that do not block it. true when missing.")
			Boolean blocksWholeRoom) {

		EventResourceAssignment toAssignment() {
			return new EventResourceAssignment(this.resourceId, !Boolean.FALSE.equals(this.blocksWholeRoom));
		}

	}

	@Schema(name = "CreateScheduleEventRequest", description = "A one-off event.")
	record CreateEventRequest(
			@NotNull @Schema(description = "CLASS, PERSONAL_TRAINING or WORKSHOP.") EventKind kind,
			@NotNull UUID serviceTypeId,
			@Size(max = MAX_TITLE_LENGTH) @Schema(description = "The service type's name when missing.") String title,
			@Min(1) @Max(Short.MAX_VALUE)
			@Schema(description = "Maximum number of participants; the service type's limit (at most the rooms' "
					+ "limit) when missing, 1 for personal training.") Integer capacity,
			@Schema(description = "PRIVATE for personal training, PUBLIC otherwise when missing.")
			EventVisibility visibility,
			@NotNull @Schema(example = "2026-11-03T17:00:00Z") Instant startsAt,
			@NotNull @Schema(example = "2026-11-03T18:00:00Z") Instant endsAt,
			List<@Valid @NotNull StaffAssignmentRequest> staff,
			List<@Valid @NotNull ResourceAssignmentRequest> resources) {

		EventDetailsRequest details() {
			return new EventDetailsRequest(this.kind, this.serviceTypeId, this.title, this.capacity, this.visibility,
					this.staff, this.resources);
		}

	}

	@Schema(name = "CreateRecurrenceSeriesRequest",
			description = "A recurring event in the studio's local time. One series is created for each weekday.")
	record CreateSeriesRequest(
			@NotNull @Schema(description = "CLASS, PERSONAL_TRAINING or WORKSHOP.") EventKind kind,
			@NotNull UUID serviceTypeId,
			@Size(max = MAX_TITLE_LENGTH) @Schema(description = "The service type's name when missing.") String title,
			@Min(1) @Max(Short.MAX_VALUE) Integer capacity,
			EventVisibility visibility,
			@NotEmpty @Schema(description = "ISO weekdays, 1 = Monday.", example = "[2, 4]")
			Set<@NotNull @Min(1) @Max(7) Integer> weekdays,
			@NotNull @Schema(type = "string", example = "18:00", description = "Studio local time, HH:mm.")
			LocalTime localStartTime,
			@NotNull @Min(5) @Max(MAX_DURATION_MINUTES) @Schema(example = "60") Integer durationMinutes,
			@Min(1) @Max(MAX_INTERVAL_WEEKS) @Schema(description = "Every how many weeks; 1 when missing.")
			Integer intervalWeeks,
			@NotNull @Schema(description = "First day of the series, not in the past.") LocalDate startsOn,
			@NotNull @Schema(description = "Last day of the series, at most a year after startsOn.") LocalDate endsOn,
			@Schema(description = "Days on which no occurrence is created, e.g. holidays or conflicting dates.")
			Set<@NotNull LocalDate> excludedDates,
			List<@Valid @NotNull StaffAssignmentRequest> staff,
			List<@Valid @NotNull ResourceAssignmentRequest> resources) {

		EventDetailsRequest details() {
			return new EventDetailsRequest(this.kind, this.serviceTypeId, this.title, this.capacity, this.visibility,
					this.staff, this.resources);
		}

	}

	/** The part of a create request shared by one-off events and series. */
	record EventDetailsRequest(EventKind kind, UUID serviceTypeId, String title, Integer capacity,
			EventVisibility visibility, List<StaffAssignmentRequest> staff,
			List<ResourceAssignmentRequest> resources) {
	}

	enum AssignmentScope {

		/** Only the given event. */
		THIS_EVENT,

		/** The given event and the series' later scheduled occurrences; it becomes the series' default. */
		THIS_AND_FOLLOWING

	}

	@Schema(name = "ChangeScheduleEventAssignmentsRequest",
			description = "Replaces the staff and rooms of an event.")
	record ChangeAssignmentsRequest(
			@NotNull @Schema(description = "The event's version the change is based on.") Integer version,
			@Schema(description = "THIS_EVENT when missing; ignored for a one-off event.") AssignmentScope scope,
			@NotNull List<@Valid @NotNull StaffAssignmentRequest> staff,
			@NotNull List<@Valid @NotNull ResourceAssignmentRequest> resources) {
	}

	@Schema(name = "ScheduleEvent", description = "A calendar event with its staff and resources.")
	record ScheduleEventResponse(
			UUID id,
			EventKind kind,
			EventStatus status,
			EventVisibility visibility,
			@Schema(example = "Pole dance początkujący") String title,
			UUID serviceTypeId,
			@Schema(example = "Pole dance - początkujący") String serviceTypeName,
			@Schema(description = "The series that generated the event; null for a one-off event.") UUID seriesId,
			@Schema(description = "The occurrence differs from its series' rule.") boolean isSeriesException,
			UUID trainingGroupId,
			Instant startsAt,
			Instant endsAt,
			@Schema(description = "Maximum number of participants; null = no limit.") Integer capacity,
			List<AssignedStaffResponse> staff,
			List<AssignedResourceResponse> resources,
			String cancelReason,
			String blockReason,
			@Schema(description = "Name of a consultation guest without an account.") String guestName,
			int version) {
	}

	@Schema(name = "ScheduleEventStaff")
	record AssignedStaffResponse(UUID staffId, @Schema(example = "Anna Kowalska") String displayName,
			StaffRole role) {
	}

	@Schema(name = "ScheduleEventResource")
	record AssignedResourceResponse(UUID resourceId, @Schema(example = "Sala pole dance") String name,
			ResourceKind kind, boolean blocksWholeRoom) {
	}

	@Schema(name = "RecurrenceSeries", description = "A recurrence rule in the studio's local time.")
	record SeriesResponse(
			UUID id,
			UUID serviceTypeId,
			String title,
			@Schema(description = "ISO weekday, 1 = Monday.") int weekday,
			@JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "18:00") LocalTime localStartTime,
			int durationMinutes,
			int intervalWeeks,
			LocalDate startsOn,
			LocalDate endsOn,
			Integer capacity,
			SeriesStatus status,
			UUID defaultStaffId,
			UUID defaultResourceId,
			@Schema(description = "Number of occurrences the series has.") long occurrenceCount) {

		static SeriesResponse from(RecurrenceSeries series, long occurrenceCount) {
			Short capacity = series.getCapacity();
			return new SeriesResponse(series.getId(), series.getServiceTypeId(), series.getTitle(), series.getWeekday(),
					series.getLocalStartTime(), series.getDurationMinutes(), series.getIntervalWeeks(),
					series.getStartsOn(), series.getEndsOn(), (capacity != null) ? Integer.valueOf(capacity) : null,
					series.getStatus(), series.getDefaultStaffId(), series.getDefaultResourceId(), occurrenceCount);
		}

	}

	@Schema(name = "RecurrencePlan", description = "The series created for a recurring event, one for each weekday.")
	record SeriesPlanResponse(List<SeriesResponse> items,
			@Schema(description = "Number of occurrences created in all the series.") int occurrenceCount) {
	}

	@Schema(name = "SchedulePlanPreview", description = "The occurrences a plan would create and their problems.")
	record PlanPreviewResponse(List<OccurrencePreview> occurrences,
			@Schema(description = "Problems of the whole plan that do not block saving, e.g. an unqualified trainer.")
			List<ScheduleIssue> warnings,
			@Schema(description = "Number of occurrences, not excluded, that cannot be saved.") int conflictCount) {
	}

	@Schema(name = "ScheduleOccurrencePreview")
	record OccurrencePreview(
			@Schema(description = "Studio local date of the occurrence.") LocalDate date,
			Instant startsAt,
			Instant endsAt,
			@Schema(description = "The date is in excludedDates; no event is created for it.") boolean excluded,
			List<ScheduleIssue> issues) {

		boolean hasBlockingIssue() {
			return this.issues.stream().anyMatch(ScheduleIssue::blocking);
		}

	}

	enum IssueType {

		/** The room is taken by another event at that time. Blocks saving. */
		RESOURCE_BUSY,

		/** The staff member runs another event at that time. Blocks saving. */
		STAFF_BUSY,

		/** The staff member is on approved leave at that time. Blocks saving. */
		STAFF_ON_LEAVE,

		/** The staff member asked for leave at that time; the request waits for a decision. */
		STAFF_LEAVE_REQUESTED,

		/** The time is outside the staff member's regular working hours. */
		STAFF_OUTSIDE_AVAILABILITY,

		/** The staff member is not qualified to run the event's service type. */
		STAFF_NOT_QUALIFIED

	}

	@Schema(name = "ScheduleIssue", description = "A problem with a planned event.")
	record ScheduleIssue(
			IssueType type,
			@Schema(description = "Whether the problem prevents saving.") boolean blocking,
			UUID staffId,
			UUID resourceId,
			@Schema(description = "The other event, for RESOURCE_BUSY and STAFF_BUSY.") UUID eventId,
			String eventTitle,
			@Schema(description = "Start of the other event or of the leave.") Instant startsAt,
			@Schema(description = "End of the other event or of the leave.") Instant endsAt) {
	}

}
