package studioforyou.pl.backend.trainings.internal;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.trainings.internal.TrainingGroupAutoCancellation.ChangeGroupAutoCancellationRequest;
import studioforyou.pl.backend.trainings.internal.TrainingGroupAutoCancellation.GroupAutoCancellationResponse;

/**
 * Fixed training groups for administrators: the list, e.g. to filter the schedule by group, and the
 * automatic cancellation of the group's classes (#136). Managing groups and their members is a
 * separate feature.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/training-groups")
@Tag(name = "trainings")
class TrainingGroupController {

	private static final String PROBLEM = "application/problem+json";

	private final JdbcClient jdbc;

	private final TrainingGroupAutoCancellation autoCancellation;

	TrainingGroupController(JdbcClient jdbc, TrainingGroupAutoCancellation autoCancellation) {
		this.jdbc = jdbc;
		this.autoCancellation = autoCancellation;
	}

	@GetMapping
	@Operation(summary = "List training groups", operationId = "listTrainingGroups")
	ListResponse<TrainingGroupResponse> listTrainingGroups(
			@Parameter(description = "Only active (true) or inactive (false) groups; all when missing.")
			@RequestParam(required = false) @Nullable Boolean isActive) {
		String where = (isActive == null) ? "" : "WHERE is_active = :active";
		var query = this.jdbc.sql("""
				SELECT id, name, service_type_id, is_active
				FROM training_group
				%s
				ORDER BY name, id
				""".formatted(where));
		if (isActive != null) {
			query = query.param("active", isActive);
		}
		List<TrainingGroupResponse> groups = query
			.query((rs, rowNum) -> new TrainingGroupResponse(rs.getObject("id", UUID.class), rs.getString("name"),
					rs.getObject("service_type_id", UUID.class), rs.getBoolean("is_active")))
			.list();
		return new ListResponse<>(groups);
	}

	@GetMapping("/{groupId}/auto-cancellation")
	@Operation(summary = "Get the automatic cancellation of a group's classes",
			operationId = "getTrainingGroupAutoCancellation",
			description = "Whether the rule cancelling classes with too few participants is switched off for every "
					+ "class of the group.")
	@ApiResponse(responseCode = "200", description = "The group's automatic cancellation.")
	@ApiResponse(responseCode = "404", description = "No such group.", content = @Content(mediaType = PROBLEM,
			schema = @Schema(implementation = ApiProblem.class)))
	GroupAutoCancellationResponse getAutoCancellation(@PathVariable UUID groupId) {
		return this.autoCancellation.get(groupId);
	}

	@PutMapping("/{groupId}/auto-cancellation")
	@Operation(summary = "Switch the automatic cancellation of a group's classes off",
			operationId = "changeTrainingGroupAutoCancellation",
			description = "disabled true keeps every class of the group, also later ones, from being cancelled "
					+ "automatically for too few participants; false makes them follow the rule of their service "
					+ "again. The group's other booking rules stay; the change creates a new version of the group's "
					+ "booking policy. Classes already cancelled automatically stay cancelled. Repeating it changes "
					+ "nothing.")
	@ApiResponse(responseCode = "200", description = "The group's automatic cancellation after the change.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when disabled is missing.", content = @Content(
			mediaType = PROBLEM, schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such group.", content = @Content(mediaType = PROBLEM,
			schema = @Schema(implementation = ApiProblem.class)))
	GroupAutoCancellationResponse changeAutoCancellation(@PathVariable UUID groupId,
			@Valid @RequestBody ChangeGroupAutoCancellationRequest request, AccountAuthentication authentication) {
		UUID actor = authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
		return this.autoCancellation.change(actor, groupId, request.disabled());
	}

	@Schema(name = "TrainingGroup", description = "A fixed group of participants, e.g. \"Pole dance beginners Tue/Thu\".")
	record TrainingGroupResponse(UUID id, @Schema(example = "Pole dance początkujący wt/czw 18:00") String name,
			UUID serviceTypeId, boolean isActive) {
	}

}
