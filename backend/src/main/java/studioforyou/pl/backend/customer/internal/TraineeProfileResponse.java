package studioforyou.pl.backend.customer.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.internal.TrainerAssignmentService.Guardian;
import studioforyou.pl.backend.customer.internal.TrainerAssignmentService.TraineeProfile;

/**
 * Profile of a participant the signed-in trainer works with.
 *
 * @param birthDate the date of birth, or {@code null} if unknown
 * @param phone the phone number, or {@code null} if unknown
 * @param emergencyContactName the emergency contact's name, or {@code null} if none
 * @param emergencyPhone the emergency contact's phone number, or {@code null} if none
 * @param guardians current guardians of a child; empty for an adult
 * @param assignedAt when the cooperation started
 */
@Schema(name = "TraineeProfile")
public record TraineeProfileResponse(UUID customerId, CustomerKind kind, @Schema(example = "Katarzyna") String firstName,
		@Schema(example = "Zielińska") String lastName, @Schema(example = "Katarzyna Zielińska") String displayName,
		@Schema(nullable = true) LocalDate birthDate, @Schema(nullable = true) String phone,
		@Schema(nullable = true) String emergencyContactName, @Schema(nullable = true) String emergencyPhone,
		List<GuardianResponse> guardians, Instant assignedAt) {

	static TraineeProfileResponse from(TraineeProfile profile) {
		return new TraineeProfileResponse(profile.id(), profile.kind(), profile.firstName(), profile.lastName(),
				profile.firstName() + " " + profile.lastName(), profile.birthDate(), profile.phone(),
				profile.emergencyContactName(), profile.emergencyPhone(),
				profile.guardians().stream().map(GuardianResponse::from).toList(), profile.assignedAt());
	}

	/** A current guardian of a child participant. */
	@Schema(name = "TraineeGuardian")
	public record GuardianResponse(UUID id, String firstName, String lastName, String displayName,
			@Schema(nullable = true) String phone, @Schema(example = "PARENT") String relation) {

		static GuardianResponse from(Guardian guardian) {
			return new GuardianResponse(guardian.id(), guardian.firstName(), guardian.lastName(),
					guardian.firstName() + " " + guardian.lastName(), guardian.phone(), guardian.relation());
		}

	}

}
