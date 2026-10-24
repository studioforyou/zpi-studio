package studioforyou.pl.backend.identity.invitation;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A valid (not used, revoked or expired) invitation, as shown before it is accepted.
 *
 * @param email the invited account's e-mail
 * @param displayName the invited person's name, or {@code null} if no profile exists yet
 * @param roles codes of the roles the account was invited with
 * @param expiresAt when the invitation link stops working
 */
public record InvitationDetails(String email, String displayName, List<String> roles, OffsetDateTime expiresAt) {
}
