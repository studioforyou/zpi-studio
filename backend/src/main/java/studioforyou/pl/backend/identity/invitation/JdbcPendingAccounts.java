package studioforyou.pl.backend.identity.invitation;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.customer.PendingAccounts;

/**
 * Reads invited accounts from {@code account} for the customer module.
 */
@Repository
class JdbcPendingAccounts implements PendingAccounts {

	private final JdbcClient jdbc;

	JdbcPendingAccounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<UUID> findInvited(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(jdbc.sql("SELECT id FROM account WHERE id IN (:accountIds) AND status = 'INVITED'")
			.param("accountIds", accountIds)
			.query(UUID.class)
			.set());
	}

}
