package studioforyou.pl.backend.membership;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Participants holding passes, for other modules' reports.
 */
@Repository
public class PassHolders {

	private final JdbcClient jdbc;

	public PassHolders(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Participants with a pass valid on at least one day of {@code [from, to]} (studio dates). Passes
	 * waiting for the payment and cancelled ones are left out; expired and suspended ones count for the
	 * days they were valid.
	 */
	public Set<UUID> findValidBetween(LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT DISTINCT customer_id
				FROM customer_pass
				WHERE status IN ('SCHEDULED', 'ACTIVE', 'SUSPENDED', 'EXPIRED')
				  AND valid_from <= :to AND (valid_until IS NULL OR valid_until >= :from)
				""")
			.param("from", from)
			.param("to", to)
			.query(UUID.class)
			.set();
	}

	/**
	 * The pass that best tells whether each of the given participants can still come, as of
	 * {@code today} (a studio date): the one valid for the longest among those usable now or later
	 * (active, scheduled or suspended, not past their validity, with entries left), otherwise the
	 * one that ended last (expired, past its validity or out of entries). Participants who never
	 * held a pass are left out; passes waiting for the payment and cancelled ones do not count.
	 */
	public Map<UUID, CurrentPass> findCurrentPasses(Collection<UUID> participantIds, LocalDate today) {
		if (participantIds.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT ON (customer_id) customer_id, product_name, status, valid_until, entries_remaining,
				       (status IN ('ACTIVE', 'SCHEDULED', 'SUSPENDED')
				        AND (valid_until IS NULL OR valid_until >= :today)
				        AND (entries_remaining IS NULL OR entries_remaining > 0)) AS usable
				FROM customer_pass
				WHERE customer_id IN (:ids) AND status IN ('SCHEDULED', 'ACTIVE', 'SUSPENDED', 'EXPIRED')
				ORDER BY customer_id, usable DESC, valid_until DESC NULLS FIRST, created_at DESC
				""")
			.param("ids", List.copyOf(participantIds))
			.param("today", today)
			.query((rs, rowNum) -> new CurrentPass(rs.getObject("customer_id", UUID.class),
					rs.getString("product_name"), rs.getBoolean("usable"),
					"SUSPENDED".equals(rs.getString("status")), rs.getObject("valid_until", LocalDate.class),
					(Integer) rs.getObject("entries_remaining", Integer.class)))
			.list()
			.stream()
			.collect(Collectors.toMap(CurrentPass::customerId, (pass) -> pass));
	}

	/**
	 * @param usable whether the participant can still use it: not past its validity, with entries left
	 * @param suspended whether it is suspended
	 * @param validUntil its last valid day; {@code null} while it has no end
	 * @param entriesRemaining entries left; {@code null} for a pass without an entry limit
	 */
	public record CurrentPass(UUID customerId, String productName, boolean usable, boolean suspended,
			@Nullable LocalDate validUntil, @Nullable Integer entriesRemaining) {
	}

}
