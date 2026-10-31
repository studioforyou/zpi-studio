package studioforyou.pl.backend.identity.accounts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.identity.AccountStatus;

/**
 * An account on the administrators' list. The name and phone come from the account's own customer
 * profile or, without one, from its staff profile.
 *
 * @param firstName the first name, or {@code null} for an account without a profile
 * @param lastName the last name, or {@code null} for an account without a profile
 * @param phone the phone number, or {@code null} if unknown
 */
@Schema(name = "AccountSummary")
public record AccountSummary(UUID id, @Schema(example = "anna@example.com") String email, AccountStatus status,
		@Schema(description = "Role codes, sorted.", example = "[\"TRAINER\"]") List<String> roles,
		@Schema(example = "Anna Kowalska", description = "First and last name, or the e-mail without a profile.") String displayName,
		@Nullable String firstName, @Nullable String lastName, @Nullable String phone, OffsetDateTime createdAt) {
}
