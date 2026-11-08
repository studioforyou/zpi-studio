package studioforyou.pl.backend.customer;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Tells which accounts are still waiting for their owner to accept the invitation. Account status
 * belongs to the identity module, which already depends on this module, so identity implements
 * this interface instead of the customer module calling identity.
 */
public interface PendingAccounts {

	/** The given accounts that have status {@code INVITED}. */
	Set<UUID> findInvited(Collection<UUID> accountIds);

}
