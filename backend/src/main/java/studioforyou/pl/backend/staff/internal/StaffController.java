package studioforyou.pl.backend.staff.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffMember;

/**
 * Staff members the administrators assign to events.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/staff")
@Tag(name = "staff")
class StaffController {

	private final StaffProfiles staffProfiles;

	StaffController(StaffProfiles staffProfiles) {
		this.staffProfiles = staffProfiles;
	}

	@GetMapping
	@Operation(summary = "List staff members", operationId = "listStaff",
			description = "Staff members sorted by last and first name, with the service types they may run.")
	ListResponse<StaffResponse> listStaff(
			@Parameter(description = "Only active (true) or inactive (false) staff; all when missing.")
			@RequestParam(required = false) @Nullable Boolean isActive) {
		List<StaffMember> members = this.staffProfiles.findAll(isActive);
		Map<UUID, Set<UUID>> qualifications = this.staffProfiles
			.findQualifications(members.stream().map(StaffMember::id).toList());
		return new ListResponse<>(members.stream()
			.map((member) -> StaffResponse.from(member, qualifications.getOrDefault(member.id(), Set.of())))
			.toList());
	}

	@Schema(name = "StaffMember", description = "A staff member who can be assigned to events.")
	record StaffResponse(UUID id, @Schema(example = "Anna") String firstName,
			@Schema(example = "Kowalska") String lastName, @Schema(example = "Anna Kowalska") String displayName,
			@Schema(description = "Calendar colour, #RRGGBB.", example = "#E91E63") String calendarColor,
			boolean isActive,
			@Schema(description = "Service types the staff member is qualified to run.") List<UUID> serviceTypeIds) {

		static StaffResponse from(StaffMember member, Set<UUID> serviceTypeIds) {
			return new StaffResponse(member.id(), member.firstName(), member.lastName(), member.displayName(),
					member.calendarColor(), member.active(), serviceTypeIds.stream().sorted().toList());
		}

	}

}
