package studioforyou.pl.backend.identity.access;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * A role of an account.
 *
 * @param code the role code
 * @param displayName the role name shown to users
 * @param grantedBy the account that granted the role, or {@code null} when it was set up outside the API
 * @param grantedAt when the role was granted
 */
@Schema(description = "A role of an account.")
public record RoleAssignment(@Schema(example = "TRAINER") String code,
		@Schema(example = "Trener") String displayName, @Nullable UUID grantedBy, Instant grantedAt) {

}
