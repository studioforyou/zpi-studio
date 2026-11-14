package studioforyou.pl.backend.identity.history;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.identity.AccountStatus;
import studioforyou.pl.backend.identity.access.OverrideEffect;

/**
 * One change of an account's status, roles or permissions. Fields another type does not have are
 * {@code null}.
 *
 * @param id identifies the entry within the history
 * @param actor who made the change; {@code null} when the system made it
 * @param fromStatus {@code STATUS_CHANGED}: the status before, {@code null} when the account was created
 * @param toStatus {@code STATUS_CHANGED}: the new status
 * @param role {@code ROLE_GRANTED}, {@code ROLE_REVOKED}: the role code
 * @param permission {@code PERMISSION_OVERRIDE_ADDED}, {@code PERMISSION_OVERRIDE_REMOVED}: the permission code
 * @param effect the added or removed override's effect
 * @param reason why, as written by the person who made the change; {@code null} when they gave none
 */
@Schema(description = "One change of an account's status, roles or permissions.")
public record AccountHistoryEntry(@Schema(example = "audit-42") String id, Instant occurredAt,
		AccountHistoryType type, Person account, @Nullable Person actor, @Nullable AccountStatus fromStatus,
		@Nullable AccountStatus toStatus, @Nullable @Schema(example = "TRAINER") String role,
		@Nullable @Schema(example = "SCHEDULE_MANAGE_ALL") String permission, @Nullable OverrideEffect effect,
		@Nullable String reason) {

	/**
	 * An account with the name of the person it belongs to.
	 *
	 * @param displayName first and last name, or the e-mail of an account without a profile
	 */
	@Schema(name = "AccountHistoryPerson", description = "An account with the name of the person it belongs to.")
	public record Person(UUID accountId, String displayName) {
	}

}
