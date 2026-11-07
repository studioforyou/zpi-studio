package studioforyou.pl.backend.membership.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.PassUsageHistory;
import studioforyou.pl.backend.shared.api.PageResponse;

/** Reads only the pass owner's tables. Reservation identifiers are resolved by booking. */
@Service
@Transactional(readOnly = true)
class DefaultPassUsageHistory implements PassUsageHistory {

	private static final String GROUPED = """
			WITH usage AS (
			  SELECT ledger.pass_id, pass.entries_total IS NULL AS unlimited,
			         CASE WHEN kind = 'CORRECTION' THEN NULL ELSE reservation_id END AS reservation_id,
			         CASE WHEN reservation_id IS NULL OR kind = 'CORRECTION' THEN ledger.id END AS ledger_id,
			         (array_agg(ledger.id ORDER BY ledger.id))[1] AS id,
			         max(occurred_at) AS occurred_at, sum(entries_delta)::INT AS entries_change,
			         count(*) FILTER (WHERE kind = 'CORRECTION') AS corrections,
			         count(*) FILTER (WHERE kind = 'ENTRY_RESERVED') AS reserved,
			         count(*) FILTER (WHERE kind = 'ENTRY_CONSUMED') AS consumed,
			         count(*) FILTER (WHERE kind = 'ENTRY_RETURNED') AS returned,
			         coalesce(sum(entries_delta) FILTER (WHERE kind = 'ENTRY_RESERVED'), 0)::INT AS reserved_delta,
			         coalesce(sum(entries_delta) FILTER (WHERE kind = 'ENTRY_CONSUMED'), 0)::INT AS consumed_delta,
			         coalesce(sum(entries_delta) FILTER (WHERE kind = 'ENTRY_RETURNED'), 0)::INT AS returned_delta
			  FROM pass_ledger ledger JOIN customer_pass pass ON pass.id = ledger.pass_id
			  WHERE ledger.pass_id IN (:passIds) AND kind IN ('ENTRY_RESERVED', 'ENTRY_CONSUMED', 'ENTRY_RETURNED', 'CORRECTION')
			  GROUP BY ledger.pass_id, pass.entries_total,
			           CASE WHEN kind = 'CORRECTION' THEN NULL ELSE reservation_id END,
			           CASE WHEN reservation_id IS NULL OR kind = 'CORRECTION' THEN ledger.id END
			)
			""";

	private final JdbcClient jdbc;

	DefaultPassUsageHistory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Pass> passes(Collection<UUID> profileIds) {
		if (profileIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT id, customer_id, variant_id, product_name FROM customer_pass WHERE customer_id IN (:ids)")
			.param("ids", profileIds).query((row, number) -> new Pass(row.getObject("id", UUID.class),
					row.getObject("customer_id", UUID.class), row.getObject("variant_id", UUID.class), row.getString("product_name")))
			.list();
	}

	@Override
	public PageResponse<Entry> list(Collection<UUID> passIds, Instant from, Instant to, int page, int size) {
		if (passIds.isEmpty()) {
			return PageResponse.of(List.of(), page, size, 0);
		}
		String where = " WHERE TRUE" + (from == null ? "" : " AND occurred_at >= :from")
				+ (to == null ? "" : " AND occurred_at < :to");
		var count = bind(jdbc.sql(GROUPED + "SELECT count(*) FROM usage" + where), passIds, from, to);
		long total = count.query(Long.class).single();
		var items = bind(jdbc.sql(GROUPED + "SELECT * FROM usage" + where
				+ " ORDER BY occurred_at DESC, id DESC LIMIT :size OFFSET :offset"), passIds, from, to)
			.param("size", size).param("offset", (long) page * size)
			.query((row, number) -> {
				boolean unlimited = row.getBoolean("unlimited");
				UUID reservation = row.getObject("reservation_id", UUID.class);
				String kind = "REVIEW";
				int reserved = row.getInt("reserved");
				int consumed = row.getInt("consumed");
				int returned = row.getInt("returned");
				int expected = unlimited ? 0 : -1;
				boolean allocation = reserved == 1 && row.getInt("reserved_delta") == expected;
				if (row.getInt("corrections") == 1) {
					kind = "CORRECTION";
				}
				else if (reservation != null && returned == 1 && allocation && consumed == 0
						&& row.getInt("returned_delta") == -expected) {
					kind = "RETURNED";
				}
				else if (reservation != null && returned == 0 && consumed == 1
						&& ((allocation && row.getInt("consumed_delta") == 0)
								|| (reserved == 0 && row.getInt("consumed_delta") == expected))) {
					kind = "CONSUMED";
				}
				else if (reservation != null && returned == 0 && consumed == 0 && allocation) {
					kind = "RESERVED";
				}
				Integer change = unlimited || "REVIEW".equals(kind) ? null : row.getInt("entries_change");
				return new Entry(row.getObject("id", UUID.class), row.getObject("pass_id", UUID.class), reservation, kind, change,
						row.getObject("occurred_at", OffsetDateTime.class).toInstant());
			}).list();
		return PageResponse.of(items, page, size, total);
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query, Collection<UUID> passIds,
			Instant from, Instant to) {
		query = query.param("passIds", passIds);
		if (from != null) {
			query = query.param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
		}
		if (to != null) {
			query = query.param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
		}
		return query;
	}

}
