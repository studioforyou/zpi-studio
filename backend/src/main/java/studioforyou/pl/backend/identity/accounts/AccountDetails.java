package studioforyou.pl.backend.identity.accounts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.identity.AccountStatus;

/**
 * One account as an administrator sees it.
 *
 * @param activatedAt when the invitation was accepted or the registration approved; {@code null} before
 * @param customerProfileId the account's own customer profile, if it has one
 * @param staffProfileId the account's staff profile, if it has one
 * @param invitationExpiresAt when the pending invitation expires; {@code null} without one
 * @param hasLogin whether a Firebase user is linked, i.e. the person can sign in once the account is active
 * @param profiles the own client profile, the staff profile, then the children, each if there is one
 */
@Schema(name = "AccountDetails")
public record AccountDetails(UUID id, String email, AccountStatus status, List<String> roles,
		@Schema(example = "Anna Kowalska", description = "First and last name, or the e-mail without a profile.") String displayName,
		@Nullable String firstName, @Nullable String lastName, @Nullable String phone, OffsetDateTime createdAt,
		@Nullable OffsetDateTime activatedAt, @Nullable UUID customerProfileId, @Nullable UUID staffProfileId,
		@Nullable OffsetDateTime invitationExpiresAt, boolean hasLogin, List<AccountProfile> profiles) {
}
