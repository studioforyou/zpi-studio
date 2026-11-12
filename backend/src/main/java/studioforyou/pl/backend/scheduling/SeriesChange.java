package studioforyou.pl.backend.scheduling;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * How one occurrence of a series differs from the series' rule, in what participants and staff see:
 * its time, its lead trainers and its rooms. Each part is null when it is as usual.
 */
@Schema(name = "SeriesChange", description = "What a one-off change of a series occurrence changed: each field is "
		+ "the usual value from the series' rule, and null when that part is as usual.")
public record SeriesChange(
		@Schema(description = "The usual start; null when the time is as usual.") @Nullable Instant usualStartsAt,
		@Schema(description = "The usual end; null when the time is as usual.") @Nullable Instant usualEndsAt,
		@Schema(description = "The usual lead trainers; null when they are as usual.") @Nullable List<String> usualInstructors,
		@Schema(description = "The usual rooms; null when they are as usual.") @Nullable List<String> usualRooms) {

	/** The change, or null when nothing participants see differs from the rule. */
	public static @Nullable SeriesChange of(@Nullable Instant usualStartsAt, @Nullable Instant usualEndsAt,
			@Nullable List<String> usualInstructors, @Nullable List<String> usualRooms) {
		if (usualStartsAt == null && usualInstructors == null && usualRooms == null) {
			return null;
		}
		return new SeriesChange(usualStartsAt, usualEndsAt, usualInstructors, usualRooms);
	}

}
