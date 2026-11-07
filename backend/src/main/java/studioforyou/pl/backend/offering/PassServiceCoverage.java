package studioforyou.pl.backend.offering;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Batch coverage of already-held variants; archiving the offer does not revoke existing coverage. */
@Repository
public class PassServiceCoverage {

	private final JdbcClient jdbc;

	public PassServiceCoverage(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Map<UUID, Set<UUID>> find(Collection<UUID> variantIds) {
		if (variantIds.isEmpty()) {
			return Map.of();
		}
		record Coverage(UUID variantId, UUID serviceTypeId) {
		}
		return jdbc.sql("""
				SELECT v.id AS variant_id, s.service_type_id
				FROM pass_product_variant v
				JOIN pass_product_service s ON s.product_id = v.product_id
				WHERE v.id IN (:ids)
				""")
			.param("ids", variantIds)
			.query(Coverage.class)
			.list().stream()
			.collect(Collectors.groupingBy(Coverage::variantId,
					Collectors.mapping(Coverage::serviceTypeId, Collectors.toSet())));
	}

}
