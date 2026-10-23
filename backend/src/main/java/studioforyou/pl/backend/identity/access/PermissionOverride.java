package studioforyou.pl.backend.identity.access;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A permission given to or taken from one account on top of its roles' defaults.
 *
 * @param permission the permission code
 * @param effect whether the account gets or loses the permission
 * @param grantedBy the account that set the override
 * @param grantedAt when the override was set
 */
@Schema(description = "A permission given to or taken from one account on top of its roles' defaults.")
public record PermissionOverride(@Schema(example = "SCHEDULE_MANAGE_ALL") String permission, OverrideEffect effect,
		UUID grantedBy, Instant grantedAt) {

}
