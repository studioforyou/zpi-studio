package studioforyou.pl.backend.customer.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

/**
 * The studio's trainers as every signed-in user may see them, e.g. to filter the class offer by
 * trainer. Only names are exposed; contact details stay with the administrators.
 */
@RestController
@Tag(name = "customer")
public class TrainerDirectoryController {

	private final TrainerAssignmentService assignments;

	public TrainerDirectoryController(TrainerAssignmentService assignments) {
		this.assignments = assignments;
	}

	@GetMapping("/api/v1/trainers")
	@Operation(summary = "List the studio's trainers", operationId = "listTrainers",
			description = "Active staff members with the TRAINER role, sorted by last and first name.")
	public ListResponse<TrainerResponse> list() {
		return new ListResponse<>(assignments.listAssignable().stream().map(TrainerResponse::from).toList());
	}

	@Schema(name = "Trainer")
	public record TrainerResponse(UUID staffId, @Schema(example = "Marek Nowak") String displayName) {

		static TrainerResponse from(StaffMember member) {
			return new TrainerResponse(member.id(), member.displayName());
		}

	}

}
