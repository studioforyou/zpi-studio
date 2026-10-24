package studioforyou.pl.backend.scheduling.internal;

import java.util.Collection;
import java.util.stream.Stream;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Transaction-scoped PostgreSQL advisory locks on staff members and resources. A request that
 * assigns them takes the locks before it checks for conflicts, so two requests that assign the
 * same staff member or room run one after another and cannot both pass the check. The locks are
 * released when the transaction ends.
 */
@Component
class ScheduleLocks {

	private final JdbcClient jdbc;

	ScheduleLocks(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void lock(Collection<?> staffIds, Collection<?> resourceIds) {
		// A fixed order keeps two requests from waiting for each other's locks.
		Stream.concat(staffIds.stream().map((id) -> "scheduling:staff:" + id),
				resourceIds.stream().map((id) -> "scheduling:resource:" + id))
			.distinct()
			.sorted()
			.forEach((key) -> this.jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
				.param("key", key)
				.query((rs) -> {
				}));
	}

}
