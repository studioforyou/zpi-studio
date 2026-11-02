package studioforyou.pl.backend.customer;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Tells which accounts have the {@code TRAINER} role. Roles belong to the identity module,
 * which already depends on this module, so identity implements this interface instead of the
 * customer module calling identity.
 */
public interface TrainerAccounts {

	/** The given accounts that have the {@code TRAINER} role. */
	Set<UUID> findTrainers(Collection<UUID> accountIds);

}
