package studioforyou.pl.backend.offering;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Historical product coverage and its policy reference; archived products still cover held credits. */
@Service
@Transactional(readOnly = true)
public class PassMakeupCoverage {

	private final JdbcClient jdbc;

	public PassMakeupCoverage(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Terms> find(UUID variantId) {
		if (variantId == null) {
			return Optional.empty();
		}
		return jdbc.sql("""
				SELECT product.id, product.kind, product.makeup_policy_id
				FROM pass_product product JOIN pass_product_variant variant ON variant.product_id = product.id
				WHERE variant.id = :id
				""").param("id", variantId).query((row, number) -> {
			UUID productId = row.getObject("id", UUID.class);
			Set<UUID> services = Set.copyOf(jdbc.sql("SELECT service_type_id FROM pass_product_service WHERE product_id = :id")
				.param("id", productId).query(UUID.class).list());
			return new Terms(row.getObject("makeup_policy_id", UUID.class), row.getString("kind"), services);
		}).optional();
	}

	public record Terms(UUID policyId, String kind, Set<UUID> serviceTypeIds) {
	}
}
