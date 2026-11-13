package studioforyou.pl.backend.staff.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the time-off endpoints. */
final class TimeOffDtos {

	static final int MAX_TEXT_LENGTH = 500;

	private TimeOffDtos() {
	}

	@Schema(name = "CreateTimeOffRequest", description = "Leave or unavailability of the signed-in trainer; also the new values of a changed request.")
	record CreateOwnTimeOffRequest(@NotNull TimeOffKind kind,
			@NotNull @Schema(description = "Start, inclusive.", example = "2026-11-09T23:00:00Z") Instant startsAt,
			@NotNull @Schema(description = "End, exclusive.", example = "2026-11-14T23:00:00Z") Instant endsAt,
			@Size(max = MAX_TEXT_LENGTH) @Schema(description = "Optional note for the administrator.",
					example = "Wyjazd rodzinny") @Nullable String reason) {
	}

	@Schema(name = "CreateStaffTimeOffRequest",
			description = "Time off an administrator records for a staff member; it is approved at once.")
	record CreateStaffTimeOffRequest(@NotNull UUID staffId, @NotNull TimeOffKind kind,
			@NotNull @Schema(description = "Start, inclusive.", example = "2026-11-09T23:00:00Z") Instant startsAt,
			@NotNull @Schema(description = "End, exclusive.", example = "2026-11-14T23:00:00Z") Instant endsAt,
			@Size(max = MAX_TEXT_LENGTH) @Nullable String reason) {
	}

	@Schema(name = "TimeOffDecisionRequest", description = "Optional note shown to the staff member.")
	record DecisionRequest(@Size(max = MAX_TEXT_LENGTH) @Schema(example = "Zastępstwo ustalone.")
	@Nullable String note) {
	}

	@Schema(name = "TimeOff", description = "Leave or unavailability of a staff member.")
	record TimeOffResponse(UUID id, UUID staffId, @Schema(example = "Anna Kowalska") String staffDisplayName,
			TimeOffKind kind, TimeOffStatus status, @Schema(description = "Start, inclusive.") Instant startsAt,
			@Schema(description = "End, exclusive.") Instant endsAt, @Nullable String reason, Instant requestedAt,
			@Schema(description = "When it was approved or rejected; null while requested.") @Nullable Instant decidedAt,
			@Schema(description = "The administrator's note on the decision.") @Nullable String decisionNote,
			@Schema(description = "Who approved or rejected it; null while requested.", example = "Beata Lis")
			@Nullable String decidedByName,
			@Schema(description = "Recorded by the studio, approved at once, rather than requested by the staff member.")
			boolean recordedByStudio) {
	}

}
