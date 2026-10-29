package studioforyou.pl.backend.customer.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.customer.internal.TrainerAssignmentService.AssignedTrainer;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

/**
 * A trainer currently assigned to a participant.
 *
 * @param staffId the trainer's staff profile
 * @param isActive whether the trainer's staff profile is still active
 * @param assignedBy the account that made the assignment
 */
@Schema(name = "CustomerTrainer")
public record CustomerTrainerResponse(UUID staffId, @Schema(example = "Marek") String firstName,
		@Schema(example = "Nowak") String lastName, @Schema(example = "Marek Nowak") String displayName,
		boolean isActive, UUID assignedBy, Instant assignedAt) {

	static CustomerTrainerResponse from(AssignedTrainer assigned) {
		StaffMember trainer = assigned.trainer();
		return new CustomerTrainerResponse(trainer.id(), trainer.firstName(), trainer.lastName(),
				trainer.displayName(), trainer.active(), assigned.assignedBy(), assigned.assignedAt());
	}

}
