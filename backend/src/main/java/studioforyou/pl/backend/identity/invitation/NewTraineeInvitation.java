package studioforyou.pl.backend.identity.invitation;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A client account a trainer has just created and the invitation sent to the person.
 *
 * @param invitationId the invitation id
 * @param accountId the new account's id (status {@code INVITED})
 * @param customerId the new participant profile, already assigned to the trainer
 * @param email the invited e-mail
 * @param displayName the participant's first and last name
 * @param expiresAt when the invitation link stops working
 */
@Schema(name = "NewTraineeInvitation")
public record NewTraineeInvitation(UUID invitationId, UUID accountId, UUID customerId,
		@Schema(example = "julia.kaminska@example.com") String email,
		@Schema(example = "Julia Kamińska") String displayName, OffsetDateTime expiresAt) {
}
