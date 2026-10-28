package studioforyou.pl.backend.identity.roles;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.customer.TrainerAccounts;

/**
 * Reads the {@code TRAINER} role from {@code account_role} for the customer module.
 */
@Repository
class JdbcTrainerAccounts implements TrainerAccounts {

	private final JdbcClient jdbc;

	JdbcTrainerAccounts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Set<UUID> findTrainers(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(jdbc.sql("""
				SELECT ar.account_id
				FROM account_role ar
				JOIN role r ON r.id = ar.role_id
				WHERE ar.account_id IN (:accountIds) AND r.code = 'TRAINER'
				""")
			.param("accountIds", accountIds)
			.query(UUID.class)
			.set());
	}

}
