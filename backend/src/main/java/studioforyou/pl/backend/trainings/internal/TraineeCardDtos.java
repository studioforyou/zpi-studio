package studioforyou.pl.backend.trainings.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the trainee card endpoints. */
final class TraineeCardDtos {

	private TraineeCardDtos() {
	}

	/**
	 * The card of a participant the signed-in trainer works with.
	 *
	 * @param version sent back with an update; {@code 0} while nobody has filled in the card
	 * @param updatedBy name of the person who last changed the goals or experience
	 * @param healthNotes current notes first (newest first), then archived ones (last archived
	 * first); {@code null} unless {@code healthAccess} is {@code GRANTED}
	 */
	@Schema(name = "TraineeCard", description = "Goals, sport experience and the health notes the participant "
			+ "shared with the trainer. One card per participant, shared by all of their current trainers.")
	record TraineeCardResponse(UUID customerId,
			@Schema(nullable = true, example = "Pierwsze podciągnięcie na drążku, redukcja 5 kg.") @Nullable String goals,
			@Schema(nullable = true, example = "Siłownia 2 lata, piłka nożna amatorsko.") @Nullable String sportExperience,
			@Schema(description = "Sent back with an update; 0 while nobody has filled in the card.") int version,
			@Schema(nullable = true) @Nullable Instant updatedAt,
			@Schema(nullable = true, example = "Marek Nowak") @Nullable String updatedBy,
			@Schema(description = "GRANTED with the CUSTOMER_HEALTH_READ permission and the participant's consent "
					+ "on this trainer's assignment; NOT_SHARED without the consent; NO_PERMISSION without the "
					+ "permission.") HealthAccess healthAccess,
			@Schema(nullable = true, description = "Current notes first (newest first), then archived ones (last "
					+ "archived first). null unless healthAccess is GRANTED, so nothing tells whether hidden notes "
					+ "exist.") @Nullable List<HealthNoteResponse> healthNotes) {
	}

	/**
	 * A health restriction or injury. Notes are never edited: one that no longer applies is archived.
	 *
	 * @param reportedBy name of the person who added the note: the participant, a guardian or a trainer
	 * @param archivedAt when the note stopped applying, or {@code null} while it applies
	 * @param archivedBy who archived the note; {@code null} while it applies or when unknown
	 */
	@Schema(name = "HealthNote")
	record HealthNoteResponse(UUID id, HealthNoteKind kind,
			@Schema(example = "Uraz lewego kolana (2023), unikać skoków.") String description, Instant reportedAt,
			@Schema(nullable = true, example = "Tomasz Lewandowski") @Nullable String reportedBy,
			@Schema(nullable = true) @Nullable Instant archivedAt,
			@Schema(nullable = true, example = "Marek Nowak") @Nullable String archivedBy) {
	}

	// A class, not a record: Jackson calls a setter only for a field present in the body, so an
	// omitted field stays unchanged while an explicit null clears it.
	@Schema(name = "UpdateTraineeCardRequest", description = "Partial update; omitted fields stay unchanged. "
			+ "Surrounding whitespace is trimmed; null or blank text clears a field.")
	static final class UpdateTraineeCardRequest {

		private @Nullable String goals;

		private boolean goalsPresent;

		private @Nullable String sportExperience;

		private boolean sportExperiencePresent;

		@NotNull
		@Min(0)
		private @Nullable Integer version;

		@Schema(nullable = true, maxLength = TraineeCardService.MAX_CARD_TEXT_LENGTH,
				description = "What the participant wants to achieve; at most 2000 characters after trimming.")
		public @Nullable String getGoals() {
			return this.goals;
		}

		public void setGoals(@Nullable String goals) {
			this.goalsPresent = true;
			this.goals = goals;
		}

		@Schema(nullable = true, maxLength = TraineeCardService.MAX_CARD_TEXT_LENGTH,
				description = "Sports the participant did or does; at most 2000 characters after trimming.")
		public @Nullable String getSportExperience() {
			return this.sportExperience;
		}

		public void setSportExperience(@Nullable String sportExperience) {
			this.sportExperiencePresent = true;
			this.sportExperience = sportExperience;
		}

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "The card's version the change is based on; 0 for a card nobody has filled in yet.")
		public @Nullable Integer getVersion() {
			return this.version;
		}

		public void setVersion(@Nullable Integer version) {
			this.version = version;
		}

		boolean goalsPresent() {
			return this.goalsPresent;
		}

		boolean sportExperiencePresent() {
			return this.sportExperiencePresent;
		}

	}

	@Schema(name = "AddHealthNoteRequest")
	record AddHealthNoteRequest(@NotNull HealthNoteKind kind,
			@NotBlank @Schema(maxLength = TraineeCardService.MAX_HEALTH_NOTE_LENGTH,
					description = "At most 1000 characters after trimming.",
					example = "Uraz lewego kolana (2023), unikać skoków.") @Nullable String description) {
	}

}
