package studioforyou.pl.backend.staff;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** The time-off workflow's contract with the scheduling module. */
public interface TimeOffSubstitutions {

	Plan preview(UUID staffId, Instant from, Instant to, @Nullable UUID timeOffId);

	Plan plan(UUID timeOffId, @Nullable UUID viewerStaffId);

	/** Takes event locks before the caller locks the staff row. */
	void prepare(UUID staffId, Instant from, Instant to, @Nullable List<UUID> selected, @Nullable UUID timeOffId);

	void replace(UUID timeOffId, UUID staffId, Instant from, Instant to, List<UUID> selected,
			boolean approved, UUID actorId);

	void activate(UUID timeOffId, UUID actorId);

	void close(UUID timeOffId, UUID actorId);

	List<Candidate> candidates(UUID timeOffId, List<UUID> eventIds);

	Plan decide(UUID timeOffId, Batch input, UUID actorId, boolean approveClaims);

	@Schema(name = "TimeOffSubstitutionPlan")
	record Plan(@Nullable UUID timeOffId, int version, String status, List<Lesson> items) { }

	@Schema(name = "TimeOffSubstitutionLesson")
	record Lesson(UUID eventId, int eventVersion, String title, String kind, String rooms,
			Instant startsAt, Instant endsAt, boolean selectable, boolean selected,
			@Nullable UUID requestId, @Nullable Integer requestVersion, String status,
			@Nullable UUID substituteStaffId, @Nullable String substituteTrainer) { }

	@Schema(name = "TimeOffSubstitutionCandidate")
	record Candidate(UUID staffId, String displayName, boolean available, @Nullable String reason,
			List<String> warnings) { }

	@Schema(name = "TimeOffSubstitutionSelection")
	record Selection(@NotNull @Min(0) Integer version,
			@NotNull @Size(max = 1000) List<@NotNull UUID> eventIds) { }

	@Schema(name = "TimeOffSubstitutionEventVersion")
	record EventVersion(@NotNull UUID eventId, @NotNull @Min(0) Integer eventVersion,
			@Nullable UUID requestId, @Nullable @Min(0) Integer requestVersion) { }

	@Schema(name = "TimeOffSubstitutionBatch")
	record Batch(@NotNull @Min(0) Integer version,
			@NotEmpty @Size(max = 1000) List<@Valid EventVersion> items,
			@Nullable UUID staffId, @Nullable @Size(max = 1000) String note) { }
}
