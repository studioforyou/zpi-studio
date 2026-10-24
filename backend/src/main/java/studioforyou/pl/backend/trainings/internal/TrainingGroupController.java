package studioforyou.pl.backend.trainings.internal;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.ListResponse;

/**
 * Read-only list of fixed training groups, e.g. to filter the schedule by group. Managing groups
 * and their members is a separate feature.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/training-groups")
@Tag(name = "trainings")
class TrainingGroupController {

	private final JdbcClient jdbc;

	TrainingGroupController(JdbcClient jdbc) {
		this.jdbc = jdbc;
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

	@Schema(name = "TrainingGroup", description = "A fixed group of participants, e.g. \"Pole dance beginners Tue/Thu\".")
	record TrainingGroupResponse(UUID id, @Schema(example = "Pole dance początkujący wt/czw 18:00") String name,
			UUID serviceTypeId, boolean isActive) {
	}

}
