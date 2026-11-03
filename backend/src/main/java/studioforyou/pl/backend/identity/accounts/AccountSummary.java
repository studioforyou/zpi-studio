package studioforyou.pl.backend.identity.accounts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.identity.AccountStatus;

/**
 * A person on the administrators' list: the owner of an account, whose own customer profile and
 * staff profile are one person, or a child the account is the guardian of. Account fields (e-mail,
 * status, creation) are the account's.
 *
 * @param id the account
 * @param profileId the person's profile: the own customer profile, else the staff profile, or the
 * child's profile; {@code null} for an account without a profile
 * @param roles the account's roles for its owner, {@code CHILD} for a child
 * @param firstName the first name, or {@code null} for an account without a profile
 * @param lastName the last name, or {@code null} for an account without a profile
 * @param phone the phone number, or {@code null} if unknown or for a child
 */
@Schema(name = "AccountSummary")
public record AccountSummary(UUID id, @Nullable UUID profileId, @Schema(example = "anna@example.com") String email, AccountStatus status,
		@Schema(description = "Role codes, sorted; CHILD for a child.", example = "[\"TRAINER\"]") List<String> roles,
		@Schema(example = "Anna Kowalska", description = "First and last name, or the e-mail without a profile.") String displayName,
		@Nullable String firstName, @Nullable String lastName, @Nullable String phone, OffsetDateTime createdAt) {
}
