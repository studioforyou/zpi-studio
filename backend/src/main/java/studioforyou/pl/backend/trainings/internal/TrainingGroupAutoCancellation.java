package studioforyou.pl.backend.trainings.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * Administrators switch the automatic cancellation of classes with too few participants off for
 * every class of a fixed group (#136). The rule is a booking policy of the group, owned by
 * {@code configuration}; the group's row lock serializes the changes of one group.
 */
@Service
class TrainingGroupAutoCancellation {

	private final JdbcClient jdbc;

	private final BookingPolicies policies;

	TrainingGroupAutoCancellation(JdbcClient jdbc, BookingPolicies policies) {
		this.jdbc = jdbc;
		this.policies = policies;
	}

	@Transactional(readOnly = true)
	public GroupAutoCancellationResponse get(UUID groupId) {
		requireGroup(groupId, "");
		return new GroupAutoCancellationResponse(groupId, this.policies.isAutoCancelDisabledForGroup(groupId));
	}

	@Transactional
	public GroupAutoCancellationResponse change(UUID actorId, UUID groupId, boolean disabled) {
		requireGroup(groupId, " FOR UPDATE");
		this.policies.changeGroupAutoCancel(groupId, disabled, actorId);
		return new GroupAutoCancellationResponse(groupId, this.policies.isAutoCancelDisabledForGroup(groupId));
	}

	private void requireGroup(UUID groupId, String lock) {
		this.jdbc.sql("SELECT id FROM training_group WHERE id = :id" + lock)
			.param("id", groupId)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Training group not found."));
	}

	@Schema(name = "TrainingGroupAutoCancellation", description = "Whether the classes of a fixed group can be "
			+ "cancelled automatically for too few participants.")
	record GroupAutoCancellationResponse(UUID trainingGroupId,
			@Schema(description = "The rule is switched off for every class of the group; false means the classes "
					+ "follow the rule of their service.") boolean disabled) {
	}

	@Schema(name = "ChangeTrainingGroupAutoCancellationRequest")
	record ChangeGroupAutoCancellationRequest(
			@Schema(description = "true switches the rule off for every class of the group; false makes them follow "
					+ "the rule of their service again.") @NotNull Boolean disabled) {
	}

}
