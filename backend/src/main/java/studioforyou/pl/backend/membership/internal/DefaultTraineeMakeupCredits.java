package studioforyou.pl.backend.membership.internal;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.TraineeMakeupCredits;
import studioforyou.pl.backend.offering.PassMakeupCoverage;

/** Reads membership-owned tables and the product coverage; nothing is changed. */
@Service
@Transactional(readOnly = true)
class DefaultTraineeMakeupCredits implements TraineeMakeupCredits {

	private final JdbcClient jdbc;

	private final PassMakeupCoverage coverage;

	DefaultTraineeMakeupCredits(JdbcClient jdbc, PassMakeupCoverage coverage) {
		this.jdbc = jdbc;
		this.coverage = coverage;
	}

	@Override
	public Summary find(UUID customerId, Instant checkedAt, LocalDate checkedOn) {
		List<CreditRow> rows = jdbc.sql("""
				SELECT credit.id, credit.source_pass_id, pass.variant_id, pass.product_name, credit.granted_at,
				       credit.expires_at, credit.source_reservation_id
				FROM makeup_credit credit JOIN customer_pass pass ON pass.id = credit.source_pass_id
				WHERE credit.customer_id = :customer AND pass.customer_id = :customer
				  AND credit.status = 'AVAILABLE' AND credit.expires_at > :now
				  AND credit.used_at IS NULL AND credit.used_reservation_id IS NULL
				ORDER BY credit.expires_at, credit.id
				""")
			.param("customer", customerId)
			.param("now", checkedAt.atOffset(ZoneOffset.UTC))
			.query((row, number) -> new CreditRow(row.getObject("id", UUID.class), row.getObject("source_pass_id", UUID.class),
					row.getObject("variant_id", UUID.class), row.getString("product_name"),
					row.getObject("granted_at", OffsetDateTime.class).toInstant(),
					row.getObject("expires_at", OffsetDateTime.class).toInstant(),
					row.getObject("source_reservation_id", UUID.class)))
			.list();
		List<PassRow> passes = jdbc.sql("""
				SELECT pass.id, pass.variant_id, pass.product_name,
				       (SELECT count(*) FROM makeup_credit credit WHERE credit.source_pass_id = pass.id) AS granted
				FROM customer_pass pass
				WHERE pass.customer_id = :customer
				  AND (pass.id IN (SELECT source_pass_id FROM makeup_credit WHERE customer_id = :customer
				                   AND status = 'AVAILABLE' AND expires_at > :now
				                   AND used_at IS NULL AND used_reservation_id IS NULL)
				       OR pass.status NOT IN ('EXPIRED', 'PENDING_PAYMENT')
				          AND (pass.valid_until IS NULL OR pass.valid_until >= :today))
				ORDER BY pass.created_at, pass.id
				""")
			.param("customer", customerId)
			.param("now", checkedAt.atOffset(ZoneOffset.UTC))
			.param("today", Date.valueOf(checkedOn))
			.query((row, number) -> new PassRow(row.getObject("id", UUID.class), row.getObject("variant_id", UUID.class),
					row.getString("product_name"), row.getInt("granted")))
			.list();

		Map<UUID, Optional<PassMakeupCoverage.Terms>> terms = new HashMap<>();
		rows.forEach(row -> terms.computeIfAbsent(row.variantId(), coverage::find));
		passes.forEach(pass -> terms.computeIfAbsent(pass.variantId(), coverage::find));
		Map<UUID, Policy> policies = new HashMap<>();
		terms.values().stream().flatMap(Optional::stream).map(PassMakeupCoverage.Terms::policyId)
			.filter(id -> id != null && !policies.containsKey(id))
			.distinct()
			.forEach(id -> policy(id).ifPresent(policy -> policies.put(id, policy)));

		List<Credit> credits = rows.stream().map(row -> {
			Optional<PassMakeupCoverage.Terms> found = terms.get(row.variantId());
			Policy policy = found.map(PassMakeupCoverage.Terms::policyId).map(policies::get).orElse(null);
			Set<UUID> services = found.map(PassMakeupCoverage.Terms::serviceTypeIds).orElse(Set.of());
			return new Credit(row.id(), row.sourcePassId(), row.productName(), row.grantedAt(), row.expiresAt(),
					row.sourceReservationId(), policy == null ? null : policy.name(), services);
		}).toList();

		Map<UUID, Limit> limits = new LinkedHashMap<>();
		for (PassRow pass : passes) {
			Policy policy = terms.get(pass.variantId()).map(PassMakeupCoverage.Terms::policyId).map(policies::get).orElse(null);
			if (policy != null && policy.maxPerPass() != null) {
				limits.put(pass.id(), new Limit(pass.id(), pass.productName(), pass.granted(), policy.maxPerPass()));
			}
		}
		List<Limit> ordered = new ArrayList<>(limits.values());
		ordered.sort(Comparator.comparing(Limit::reached).reversed());
		return new Summary(credits, ordered);
	}

	private Optional<Policy> policy(UUID id) {
		return jdbc.sql("SELECT name, max_per_pass FROM makeup_policy WHERE id = :id")
			.param("id", id)
			.query((row, number) -> new Policy(row.getString("name"), row.getObject("max_per_pass", Integer.class)))
			.optional();
	}

	private record CreditRow(UUID id, UUID sourcePassId, UUID variantId, String productName, Instant grantedAt,
			Instant expiresAt, UUID sourceReservationId) {
	}

	private record PassRow(UUID id, UUID variantId, String productName, int granted) {
	}

	private record Policy(String name, Integer maxPerPass) {
	}

}
