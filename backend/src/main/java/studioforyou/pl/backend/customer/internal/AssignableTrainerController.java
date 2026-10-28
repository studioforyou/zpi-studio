package studioforyou.pl.backend.customer.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

/**
 * Trainers the administrators can choose from when assigning a trainer to a participant.
 */
@RestController
@Tag(name = "customer")
@PreAuthorize("hasRole('ADMIN')")
public class AssignableTrainerController {

	private final TrainerAssignmentService assignments;

	public AssignableTrainerController(TrainerAssignmentService assignments) {
		this.assignments = assignments;
	}

	@GetMapping("/api/v1/customers/assignable-trainers")
	@Operation(summary = "List trainers that can be assigned to participants", operationId = "listAssignableTrainers",
			description = "Active staff members with the TRAINER role, sorted by last and first name.")
	public ListResponse<AssignableTrainerResponse> list() {
		return new ListResponse<>(assignments.listAssignable().stream().map(AssignableTrainerResponse::from).toList());
	}

	@Schema(name = "AssignableTrainer")
	public record AssignableTrainerResponse(UUID staffId, @Schema(example = "Marek") String firstName,
			@Schema(example = "Nowak") String lastName, @Schema(example = "Marek Nowak") String displayName) {

		static AssignableTrainerResponse from(StaffMember member) {
			return new AssignableTrainerResponse(member.id(), member.firstName(), member.lastName(),
					member.displayName());
		}

	}

}
