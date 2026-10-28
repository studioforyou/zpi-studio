package studioforyou.pl.backend.identity;

import java.time.OffsetDateTime;

/**
 * Published inside the transaction that creates an invited account. Listeners (the invitation
 * e-mail) run in the same transaction: if one fails, the account and invitation are rolled back.
 * The link carries the only plain copy of the token, so the event is never stored.
 *
 * @param email the invited person's e-mail
 * @param firstName the invited person's first name
 * @param role the role code the account was invited with, e.g. {@code CLIENT}
 * @param link the invitation link with the token
 * @param expiresAt when the link stops working
 */
public record AccountInvited(String email, String firstName, String role, String link, OffsetDateTime expiresAt) {
}
