package studioforyou.pl.backend.offering.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Names and status of the make-up policies products refer to. The policies themselves are
 * managed by the membership module, which depends on this one, so they are read directly.
 */
@Component
class MakeupPolicyLookup {

	private final JdbcClient jdbc;

	MakeupPolicyLookup(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	boolean isActive(UUID policyId) {
		return jdbc.sql("SELECT is_active FROM makeup_policy WHERE id = :id")
			.param("id", policyId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	Map<UUID, String> names(Collection<PassProduct> products) {
		List<UUID> ids = products.stream()
			.map(PassProduct::getMakeupPolicyId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("SELECT id, name FROM makeup_policy WHERE id IN (:ids)")
			.param("ids", ids)
			.query((row, number) -> Map.entry(row.getObject("id", UUID.class), row.getString("name")))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}
