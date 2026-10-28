package studioforyou.pl.backend.identity.admin;

import java.util.UUID;

/**
 * Response of {@code POST /api/v1/admin-accounts}.
 *
 * @param password the generated password; returned only here, never stored by the backend. The
 * creating administrator passes it on privately, and the new administrator can change it with
 * the password reset on the login page.
 */
public record NewAdminAccount(UUID accountId, UUID staffProfileId, String email, String password) {
}
