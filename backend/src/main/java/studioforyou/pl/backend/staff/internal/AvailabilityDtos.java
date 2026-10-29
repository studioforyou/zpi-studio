package studioforyou.pl.backend.staff.internal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies of the availability endpoints. Times are in the studio's local
 * time, {@code HH:mm}.
 */
final class AvailabilityDtos {

	private AvailabilityDtos() {
	}

	/** Whether a slot repeats every week or applies on a single date. */
	enum AvailabilityType {

		WEEKLY, ONE_OFF

	}

	@Schema(name = "CreateAvailabilityRequest",
			description = "Send weekday for hours that repeat every week, or date for a single extra day; never both.")
	record CreateAvailabilityRequest(
			@Min(1) @Max(7) @Schema(description = "ISO weekday, 1 = Monday. For weekly hours.", example = "2")
			@Nullable Integer weekday,
			@Schema(description = "The single day of one-off hours, today or later.", example = "2026-11-07")
			@Nullable LocalDate date,
			@NotNull @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "17:00") LocalTime startTime,
			@NotNull @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "21:00") LocalTime endTime,
			@Schema(description = "Weekly hours only: the first day they apply, today or later. Today when missing.")
			@Nullable LocalDate validFrom,
			@Schema(description = "Weekly hours only: the last day they apply; no end when missing.")
			@Nullable LocalDate validTo) {
	}

	// A class, not a record: Jackson calls a setter only for a field present in the body, so a
	// missing validTo stays unchanged while an explicit null removes the end date.
	@Schema(name = "UpdateAvailabilityRequest", description = "Partial update; missing fields stay unchanged.")
	static final class UpdateAvailabilityRequest {

		@JsonFormat(pattern = "HH:mm")
		@Schema(type = "string", example = "17:30")
		private LocalTime startTime;

		@JsonFormat(pattern = "HH:mm")
		@Schema(type = "string", example = "20:30")
		private LocalTime endTime;

		@Schema(description = "Weekly hours only: the last day they apply; null removes the end.",
				types = { "string", "null" }, format = "date")
		private Optional<LocalDate> validTo;

		public LocalTime getStartTime() {
			return this.startTime;
		}

		public void setStartTime(LocalTime startTime) {
			this.startTime = startTime;
		}

		public LocalTime getEndTime() {
			return this.endTime;
		}

		public void setEndTime(LocalTime endTime) {
			this.endTime = endTime;
		}

		public Optional<LocalDate> getValidTo() {
			return this.validTo;
		}

		public void setValidTo(Optional<LocalDate> validTo) {
			this.validTo = validTo;
		}

	}

	/**
	 * @param date the day of a one-off slot; {@code null} for weekly hours
	 * @param validTo the last day the slot applies, or {@code null} with no end
	 */
	@Schema(name = "AvailabilitySlot", description = "Hours in which a trainer can run classes and trainings.")
	record AvailabilitySlotResponse(UUID id, AvailabilityType type,
			@Schema(description = "ISO weekday, 1 = Monday.", example = "2") int weekday,
			@Schema(types = { "string", "null" }, format = "date") @Nullable LocalDate date,
			@JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "17:00") LocalTime startTime,
			@JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "21:00") LocalTime endTime,
			LocalDate validFrom, @Schema(types = { "string", "null" }, format = "date") @Nullable LocalDate validTo) {
	}

}
