package studioforyou.pl.backend.identity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Account counts and statuses for reports.
 */
@Repository
public class AccountStatistics {

	private final JdbcClient jdbc;

	public AccountStatistics(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Accounts with the {@code CLIENT} role activated in {@code [from, to)}: an accepted invitation or
	 * an approved registration. Invitations not accepted yet and rejected registrations are left out.
	 */
	public long countClientsActivated(Instant from, Instant to) {
		return jdbc.sql("""
				SELECT count(*)
				FROM account a
				WHERE a.activated_at >= :from AND a.activated_at < :to
				  AND EXISTS (
				    SELECT 1 FROM account_role ar JOIN role r ON r.id = ar.role_id
				    WHERE ar.account_id = a.id AND r.code = 'CLIENT'
				  )
				""")
			.param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
			.param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
			.query(Long.class)
			.single();
	}

	/** Accounts among the given ones whose status is not {@code ACTIVE}, e.g. suspended or closed. */
	public Set<UUID> findNotActive(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Set.of();
		}
		return jdbc.sql("SELECT id FROM account WHERE id IN (:ids) AND status <> 'ACTIVE'")
			.param("ids", List.copyOf(accountIds))
			.query(UUID.class)
			.set();
	}

}
