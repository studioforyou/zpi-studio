package studioforyou.pl.backend.customer.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.customer.internal.TrainerAssignmentService.AssignedTrainee;

/**
 * A participant the signed-in trainer works with.
 *
 * @param customerId the participant's profile
 * @param birthDate the date of birth, or {@code null} if unknown
 * @param isActive whether the participant's profile is still active
 * @param assignedBy the account that made the assignment
 */
@Schema(name = "Trainee")
public record TraineeResponse(UUID customerId, CustomerKind kind, @Schema(example = "Katarzyna") String firstName,
		@Schema(example = "Zielińska") String lastName, @Schema(example = "Katarzyna Zielińska") String displayName,
		LocalDate birthDate, boolean isActive, UUID assignedBy, Instant assignedAt) {

	static TraineeResponse from(AssignedTrainee assigned) {
		ProfileDetails customer = assigned.customer();
		return new TraineeResponse(customer.id(), customer.kind(), customer.firstName(), customer.lastName(),
				customer.displayName(), customer.birthDate(), customer.active(), assigned.assignedBy(),
				assigned.assignedAt());
	}

}
