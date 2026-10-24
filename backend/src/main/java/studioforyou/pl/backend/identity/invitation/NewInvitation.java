package studioforyou.pl.backend.identity.invitation;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An invitation just created and sent.
 *
 * @param invitationId the invitation id
 * @param accountId the new account's id (status {@code INVITED})
 * @param email the invited e-mail
 * @param expiresAt when the invitation link stops working
 */
public record NewInvitation(UUID invitationId, UUID accountId, String email, OffsetDateTime expiresAt) {
}
