package studioforyou.pl.backend.membership;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Passes sold or assigned to participants, for other modules' reports.
 */
@Repository
public class PassSales {

	private final JdbcClient jdbc;

	public PassSales(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Passes that became the participant's in {@code [start, end)}: bought and paid for, or assigned
	 * by the studio staff. The moment is the activation of the pass, i.e. the payment of its order or
	 * its assignment. Passes still waiting for the payment and cancelled ones are left out; expired and
	 * suspended ones still count as sold.
	 */
	public List<SoldPass> findSoldBetween(Instant start, Instant end) {
		return jdbc.sql("""
				SELECT p.id, p.variant_id, a.sold_at, p.price_paid, p.order_item_id IS NOT NULL AS ordered
				FROM customer_pass p
				JOIN (SELECT pass_id, min(occurred_at) AS sold_at
				      FROM pass_ledger
				      WHERE kind = 'ACTIVATION'
				      GROUP BY pass_id) a ON a.pass_id = p.id
				WHERE p.status NOT IN ('PENDING_PAYMENT', 'CANCELLED')
				  AND a.sold_at >= :start AND a.sold_at < :end
				ORDER BY a.sold_at, p.id
				""")
			.param("start", OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
			.param("end", OffsetDateTime.ofInstant(end, ZoneOffset.UTC))
			.query((rs, row) -> new SoldPass(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
					rs.getObject("sold_at", OffsetDateTime.class).toInstant(), rs.getBigDecimal("price_paid"),
					rs.getBoolean("ordered")))
			.list();
	}

	/**
	 * A pass sold or assigned.
	 *
	 * @param soldAt when the pass was activated: its order was paid or the staff assigned it
	 * @param pricePaid the price actually paid; zero for a pass given for free
	 * @param ordered whether the client bought it through an order, rather than the staff assigning it
	 */
	public record SoldPass(UUID passId, UUID variantId, Instant soldAt, BigDecimal pricePaid, boolean ordered) {
	}

}
