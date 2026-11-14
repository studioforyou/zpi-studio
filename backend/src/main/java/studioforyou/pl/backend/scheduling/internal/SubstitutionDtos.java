package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

class SubstitutionDtos {

	@Schema(name = "SubstitutionAction")
	record Action(@NotNull @Min(0) Integer version, @Size(max = 1000) @Nullable String note) {
	}

	@Schema(name = "AssignSubstitution")
	record Assignment(@NotNull @Min(0) Integer version, @NotNull UUID staffId, @Size(max = 1000) @Nullable String note) {
	}

	@Schema(name = "CancelStudioEvent")
	record Cancellation(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 1000) String reason) {
	}

	@Schema(name = "SubstitutionOffer")
	record Offer(UUID id, UUID eventId, int version, int eventVersion, String status, String kind, String title, String rooms,
			Instant startsAt, Instant endsAt, UUID originalStaffId, String originalTrainer,
			@Nullable UUID substituteStaffId, @Nullable String substituteTrainer, boolean approvalRequired,
			boolean canClaim, boolean canWithdraw, @Nullable String unavailableReason, List<String> warnings,
			@Nullable String decisionNote) {
	}

	@Schema(name = "SubstitutionCandidate")
	record Candidate(UUID staffId, String displayName, boolean available, @Nullable String reason,
			List<String> warnings) {
	}
}
