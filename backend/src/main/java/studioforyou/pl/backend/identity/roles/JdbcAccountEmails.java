package studioforyou.pl.backend.identity.roles;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.customer.AccountEmails;

/**
 * Reads account e-mail addresses from {@code account} for the customer module.
 */
@Repository
class JdbcAccountEmails implements AccountEmails {

	private final JdbcClient jdbc;

	JdbcAccountEmails(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<UUID, String> emailsOf(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("SELECT id, email FROM account WHERE id IN (:ids)")
			.param("ids", accountIds)
			.query((rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("email")))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Override
	public Set<UUID> findByEmailContaining(String fragment) {
		if (fragment == null || fragment.isBlank()) {
			return Set.of();
		}
		String escaped = fragment.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return Set.copyOf(jdbc.sql("SELECT id FROM account WHERE email ILIKE :pattern")
			.param("pattern", "%" + escaped + "%")
			.query(UUID.class)
			.set());
	}

}
