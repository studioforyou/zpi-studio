package studioforyou.pl.backend.membership.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting;
import studioforyou.pl.backend.offering.PassMakeupCoverage;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

@Service
@Transactional(readOnly = true)
class DefaultPersonalTrainingAccounting implements PersonalTrainingAccounting {

	private final JdbcClient jdbc;
	private final PassMakeupCoverage coverage;
	private final CustomerPassLock passLock;
	private final BookingEntitlements entries;

	DefaultPersonalTrainingAccounting(JdbcClient jdbc, PassMakeupCoverage coverage,
			CustomerPassLock passLock, BookingEntitlements entries) {
		this.jdbc = jdbc;
		this.coverage = coverage;
		this.passLock = passLock;
		this.entries = entries;
	}

	@Override
	public Quote preview(UUID customerId, UUID passId, UUID creditId, UUID reservationId, boolean inTime, Instant now) {
		if (passId != null && creditId != null) {
			throw new ApiException(ErrorCode.CANCELLATION_TERMS_UNAVAILABLE, "The reservation has inconsistent funding.");
		}
		if (creditId != null) {
			var credit = credit(customerId, creditId, false);
			boolean returnable = inTime && "USED".equals(credit.status())
					&& reservationId.equals(credit.reservationId()) && credit.expiresAt().isAfter(now);
			return new Quote(returnable ? Effect.MAKEUP_RETURNED : Effect.ENTRY_FORFEITED, null,
					returnable ? null : "MAKEUP_NOT_RETURNABLE", credit.passName());
		}
		if (passId == null) {
			return new Quote(Effect.NO_ENTRY, null, null, null);
		}
		var pass = jdbc.sql("SELECT variant_id, product_name, entries_total FROM customer_pass WHERE id = :id AND customer_id = :customer")
			.param("id", passId).param("customer", customerId)
			.query((rs, n) -> new Pass(rs.getObject("variant_id", UUID.class), rs.getString("product_name"),
					rs.getObject("entries_total") != null)).optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass not found."));
		boolean allocated = jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM pass_ledger WHERE pass_id = :pass AND reservation_id = :reservation
				 AND kind IN ('ENTRY_RESERVED', 'ENTRY_CONSUMED'))
				AND NOT EXISTS (SELECT 1 FROM pass_ledger WHERE pass_id = :pass AND reservation_id = :reservation
				 AND kind = 'ENTRY_RETURNED')
				""").param("pass", passId).param("reservation", reservationId).query(Boolean.class).single();
		if (!pass.finite() || !allocated) {
			return new Quote(Effect.NO_ENTRY, null, allocated ? null : "NO_ALLOCATED_ENTRY", pass.name());
		}
		if (!inTime) {
			return new Quote(Effect.ENTRY_FORFEITED, null, "LATE_CANCELLATION", pass.name());
		}
		var product = coverage.find(pass.variantId()).orElse(null);
		Policy policy = product == null || product.policyId() == null ? null : jdbc.sql("""
				SELECT validity_days, max_per_pass, grant_on_in_time_cancel, is_active
				FROM makeup_policy WHERE id = :id
				""").param("id", product.policyId()).query((rs, n) -> new Policy(rs.getInt("validity_days"),
					rs.getObject("max_per_pass", Integer.class), rs.getBoolean("grant_on_in_time_cancel"),
					rs.getBoolean("is_active"))).optional().orElse(null);
		if (policy == null || !policy.active() || !policy.grant() || policy.days() <= 0) {
			return new Quote(Effect.ENTRY_FORFEITED, null, "MAKEUP_NOT_GRANTED", pass.name());
		}
		long granted = jdbc.sql("SELECT count(*) FROM makeup_credit WHERE source_pass_id = :id")
			.param("id", passId).query(Long.class).single();
		if (policy.limit() != null && granted >= policy.limit()) {
			return new Quote(Effect.ENTRY_FORFEITED, null, "MAKEUP_LIMIT_REACHED", pass.name());
		}
		return new Quote(Effect.MAKEUP_GRANTED, policy.days(), null, pass.name());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancel(UUID customerId, UUID passId, UUID creditId, UUID reservationId, boolean inTime,
			Quote expected, UUID actorId, Instant now) {
		if (creditId != null) {
			credit(customerId, creditId, true);
		}
		else if (passId != null) {
			passLock.acquire(customerId, passId);
		}
		Instant checkedAt = Instant.now();
		Quote actual = preview(customerId, passId, creditId, reservationId, inTime, checkedAt);
		if (actual.effect() != expected.effect() || !java.util.Objects.equals(actual.validityDays(), expected.validityDays())) {
			throw new ApiException(ErrorCode.CANCELLATION_CONSEQUENCE_CHANGED,
					"The cancellation consequence changed. Confirm it again.", Map.of("effect", actual.effect()));
		}
		if (actual.effect() == Effect.MAKEUP_RETURNED) {
			jdbc.sql("UPDATE makeup_credit SET status = 'AVAILABLE', used_at = NULL, used_reservation_id = NULL WHERE id = :id")
				.param("id", creditId).update();
		}
		if (passId != null) {
			entries.consume(customerId, passId, reservationId, actorId);
		}
		if (actual.effect() == Effect.MAKEUP_GRANTED) {
			jdbc.sql("""
					INSERT INTO makeup_credit (customer_id, source_pass_id, source_reservation_id, granted_by, granted_at, expires_at)
					VALUES (:customer, :pass, :reservation, :actor, :now, :expires)
					""").param("customer", customerId).param("pass", passId).param("reservation", reservationId)
				.param("actor", actorId).param("now", checkedAt.atOffset(ZoneOffset.UTC))
				.param("expires", checkedAt.plus(Duration.ofDays(actual.validityDays())).atOffset(ZoneOffset.UTC)).update();
		}
	}

	private Credit credit(UUID customerId, UUID creditId, boolean lock) {
		return jdbc.sql("""
				SELECT c.status, c.used_reservation_id, c.expires_at, p.product_name
				FROM makeup_credit c JOIN customer_pass p ON p.id = c.source_pass_id
				WHERE c.id = :id AND c.customer_id = :customer AND p.customer_id = :customer
				""" + (lock ? " FOR UPDATE OF c" : ""))
			.param("id", creditId).param("customer", customerId)
			.query((rs, n) -> new Credit(rs.getString("status"), rs.getObject("used_reservation_id", UUID.class),
					rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("product_name")))
			.optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Make-up credit not found."));
	}

	private record Pass(UUID variantId, String name, boolean finite) {}
	private record Policy(int days, Integer limit, boolean grant, boolean active) {}
	private record Credit(String status, UUID reservationId, Instant expiresAt, String passName) {}
}
