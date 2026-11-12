package studioforyou.pl.backend.membership;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import studioforyou.pl.backend.offering.PassServiceCoverage;

/**
 * Passes coming to an end, for reminders to buy the next one. A pass counts as followed when the
 * same participant already holds another usable pass (active or scheduled, with entries left or
 * without a limit) that lasts longer and covers every service the ending pass covers, e.g. a
 * new Pole Dance pass or an Open pass after a Pole Dance pass, but not a personal training
 * package. Such a participant needs no reminder.
 */
@Repository
public class ExpiringPasses {

	/** A pass stays usable while its stored status is one of these and its dates allow it. */
	private static final String RUNNING = "('ACTIVE', 'SCHEDULED')";

	private final JdbcClient jdbc;

	private final PassServiceCoverage coverage;

	public ExpiringPasses(JdbcClient jdbc, PassServiceCoverage coverage) {
		this.jdbc = jdbc;
		this.coverage = coverage;
	}

	/**
	 * Usable passes whose last day is in {@code [from, to]} (studio dates). Passes with no entries
	 * left are left out, because they cannot be used anyway, and so are followed passes.
	 */
	public List<ExpiringPass> findExpiringBetween(LocalDate from, LocalDate to) {
		List<Held> expiring = jdbc.sql("""
				SELECT id, customer_id, variant_id, product_name, valid_until, created_at
				FROM customer_pass
				WHERE status IN %s
				  AND valid_until BETWEEN :from AND :to
				  AND (entries_remaining IS NULL OR entries_remaining > 0)
				ORDER BY valid_until, id
				""".formatted(RUNNING))
			.param("from", from)
			.param("to", to)
			.query((rs, rowNum) -> new Held(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
					rs.getObject("variant_id", UUID.class), rs.getString("product_name"),
					rs.getObject("valid_until", LocalDate.class),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.list();
		if (expiring.isEmpty()) {
			return List.of();
		}
		List<Held> usable = usablePasses(expiring.stream().map(Held::customerId).collect(Collectors.toSet()));
		Map<UUID, Set<UUID>> services = services(expiring, usable);
		return expiring.stream()
			.filter((pass) -> !followed(pass, pass.validUntil(), usable, services))
			.map((pass) -> new ExpiringPass(pass.id(), pass.customerId(), pass.productName(), pass.validUntil(),
					pass.createdAt()))
			.toList();
	}

	/**
	 * Whether the participant holding the pass already has another usable pass that is still
	 * valid after {@code day} and covers every service of this one, e.g. one bought after the last
	 * entry of this one was used.
	 */
	public boolean isFollowed(UUID passId, LocalDate day) {
		Optional<Held> pass = jdbc.sql("""
				SELECT id, customer_id, variant_id, product_name, valid_until, created_at
				FROM customer_pass WHERE id = :id
				""")
			.param("id", passId)
			.query((rs, rowNum) -> new Held(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
					rs.getObject("variant_id", UUID.class), rs.getString("product_name"),
					rs.getObject("valid_until", LocalDate.class),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.optional();
		if (pass.isEmpty()) {
			return false;
		}
		List<Held> usable = usablePasses(Set.of(pass.get().customerId()));
		return followed(pass.get(), day, usable, services(List.of(pass.get()), usable));
	}

	/** Usable passes of the participants, with entries left or without a limit. */
	private List<Held> usablePasses(Collection<UUID> customerIds) {
		return jdbc.sql("""
				SELECT id, customer_id, variant_id, product_name, valid_until, created_at
				FROM customer_pass
				WHERE customer_id IN (:customers)
				  AND status IN %s
				  AND valid_until IS NOT NULL
				  AND (entries_remaining IS NULL OR entries_remaining > 0)
				""".formatted(RUNNING))
			.param("customers", customerIds)
			.query((rs, rowNum) -> new Held(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
					rs.getObject("variant_id", UUID.class), rs.getString("product_name"),
					rs.getObject("valid_until", LocalDate.class),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.list();
	}

	private Map<UUID, Set<UUID>> services(Collection<Held> passes, Collection<Held> others) {
		Set<UUID> variants = new HashSet<>();
		passes.forEach((pass) -> variants.add(pass.variantId()));
		others.forEach((pass) -> variants.add(pass.variantId()));
		return coverage.find(variants);
	}

	/** Whether another usable pass valid after {@code after} covers every service of {@code pass}. */
	private static boolean followed(Held pass, LocalDate after, List<Held> usable, Map<UUID, Set<UUID>> services) {
		Set<UUID> needed = services.getOrDefault(pass.variantId(), Set.of());
		return usable.stream()
			.filter((other) -> other.customerId().equals(pass.customerId()) && !other.id().equals(pass.id()))
			.filter((other) -> other.validUntil().isAfter(after))
			.anyMatch((other) -> services.getOrDefault(other.variantId(), Set.of()).containsAll(needed));
	}

	private record Held(UUID id, UUID customerId, UUID variantId, String productName, LocalDate validUntil,
			Instant createdAt) {
	}

	/**
	 * @param id the pass
	 * @param customerId the participant holding it
	 * @param productName the pass name the participant knows
	 * @param validUntil its last day
	 * @param createdAt when it was assigned or ordered
	 */
	public record ExpiringPass(UUID id, UUID customerId, String productName, LocalDate validUntil,
			Instant createdAt) {
	}

}
