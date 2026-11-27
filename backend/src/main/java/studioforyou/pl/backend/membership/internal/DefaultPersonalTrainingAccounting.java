package studioforyou.pl.backend.membership.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.CustomerPassStatus;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting;
import studioforyou.pl.backend.offering.PassMakeupCoverage;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

@Service
@Transactional(readOnly = true)
class DefaultPersonalTrainingAccounting implements PersonalTrainingAccounting {

	private final JdbcClient jdbc;
	private final PassMakeupCoverage coverage;
	private final CustomerPassLock passLock;
	private final BookingEntitlements entries;
	private final PassLedgerRepository ledger;
	private final CustomerPassRepository passes;
	private final StudioSettings studio;

	DefaultPersonalTrainingAccounting(JdbcClient jdbc, PassMakeupCoverage coverage, CustomerPassLock passLock,
			BookingEntitlements entries, PassLedgerRepository ledger, CustomerPassRepository passes,
			StudioSettings studio) {
		this.jdbc = jdbc;
		this.coverage = coverage;
		this.passLock = passLock;
		this.entries = entries;
		this.ledger = ledger;
		this.passes = passes;
		this.studio = studio;
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

	@Override
	public DisputeTerms disputeTerms(UUID customerId, UUID passId, UUID creditId, UUID reservationId, Instant now) {
		if (passId != null && creditId != null) {
			throw new ApiException(ErrorCode.CANCELLATION_TERMS_UNAVAILABLE, "The reservation has inconsistent funding.");
		}
		if (creditId != null) {
			Credit credit = credit(customerId, creditId, false);
			boolean expired = !credit.expiresAt().isAfter(now);
			Integer days = makeupDays(credit.variantId(), credit.finite());
			return new DisputeTerms(DisputeFunding.MAKEUP, credit.passName(), null, null, credit.expiresAt(),
					new DisputeOption(!expired, DisputeEffect.MAKEUP_RETURNED, expired ? "MAKEUP_EXPIRED" : null),
					makeupOption(days), days);
		}
		if (passId == null) {
			return new DisputeTerms(DisputeFunding.NONE, null, null, null, null,
					new DisputeOption(true, DisputeEffect.NO_CHANGE, "NOT_FUNDED"),
					new DisputeOption(false, DisputeEffect.MAKEUP_GRANTED, "NOT_FUNDED"), null);
		}
		DisputedPass pass = disputedPass(customerId, passId);
		if (pass.remaining() == null) {
			return new DisputeTerms(DisputeFunding.UNLIMITED_PASS, pass.name(), null, pass.validUntil(), null,
					new DisputeOption(true, DisputeEffect.NO_CHANGE, "UNLIMITED_PASS"),
					new DisputeOption(false, DisputeEffect.MAKEUP_GRANTED, "UNLIMITED_PASS"), null);
		}
		Integer days = makeupDays(pass.variantId(), true);
		DisputeOption upheld = charged(passId, reservationId) <= 0
				? new DisputeOption(true, DisputeEffect.NO_CHANGE, "NO_ALLOCATED_ENTRY")
				: new DisputeOption(true, DisputeEffect.ENTRY_RETURNED, notice(pass));
		return new DisputeTerms(DisputeFunding.PASS, pass.name(), pass.remaining(), pass.validUntil(), null, upheld,
				makeupOption(days), days);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public DisputeEffect settleDispute(UUID customerId, UUID passId, UUID creditId, UUID reservationId,
			DisputeResolution resolution, Integer makeupValidityDays, String reason, UUID actorId, Instant now) {
		Credit credit = creditId == null ? null : credit(customerId, creditId, true);
		if (passId != null) {
			passLock.acquire(customerId, passId);
		}
		DisputeOption option = disputeTerms(customerId, passId, creditId, reservationId, now).option(resolution);
		if (!option.allowed()) {
			throw new ApiException(ErrorCode.DISPUTE_RESOLUTION_UNAVAILABLE,
					"This decision is not available for the session's funding.", Map.of("notice", option.notice()));
		}
		if (resolution == DisputeResolution.UPHELD_MAKEUP && (makeupValidityDays == null || makeupValidityDays < 1)) {
			throw ApiException.invalidField("makeupValidityDays", FieldErrorCode.REQUIRED,
					"A granted make-up credit needs its validity in days.");
		}
		if (passId != null) {
			// The session counts as held either way; a refund below is a separate correction.
			entries.consume(customerId, passId, reservationId, actorId);
		}
		switch (option.effect()) {
			case ENTRY_RETURNED -> {
				CustomerPass pass = passLock.acquire(customerId, passId);
				short refund = (short) charged(passId, reservationId);
				pass.returnEntries(refund);
				ledger.save(PassLedgerEntry.disputeRefund(passId, reservationId, refund, reason, actorId));
				passes.flush();
			}
			// A replacement with the same expiry keeps the used credit linked to the session it paid for.
			case MAKEUP_RETURNED -> grantCredit(customerId, credit.sourcePassId(), reservationId, actorId, now,
					credit.expiresAt());
			case MAKEUP_GRANTED -> grantCredit(customerId, passId != null ? passId : credit.sourcePassId(), reservationId,
					actorId, now, now.plus(Duration.ofDays(makeupValidityDays)));
			case NO_CHANGE -> {
			}
		}
		return option.effect();
	}

	private void grantCredit(UUID customerId, UUID sourcePassId, UUID reservationId, UUID actorId, Instant now,
			Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO makeup_credit (customer_id, source_pass_id, source_reservation_id, granted_by, granted_at, expires_at)
				VALUES (:customer, :pass, :reservation, :actor, :now, :expires)
				""").param("customer", customerId).param("pass", sourcePassId).param("reservation", reservationId)
			.param("actor", actorId).param("now", now.atOffset(ZoneOffset.UTC))
			.param("expires", expiresAt.atOffset(ZoneOffset.UTC)).update();
	}

	/** Entries the reservation still holds from the pass, after every return and refund. */
	private int charged(UUID passId, UUID reservationId) {
		return jdbc.sql("""
				SELECT COALESCE(-SUM(entries_delta), 0)::INT FROM pass_ledger
				WHERE pass_id = :pass AND reservation_id = :reservation
				  AND kind IN ('ENTRY_RESERVED', 'ENTRY_CONSUMED', 'ENTRY_RETURNED', 'CORRECTION')
				""").param("pass", passId).param("reservation", reservationId).query(Integer.class).single();
	}

	/** The suggested validity of a credit from the pass, or {@code null} when its credits could not book anything. */
	private Integer makeupDays(UUID variantId, boolean finite) {
		var product = finite ? coverage.find(variantId).orElse(null) : null;
		if (product == null || "TIME_PASS".equals(product.kind()) || "VOUCHER".equals(product.kind())) {
			return null;
		}
		Integer days = product.policyId() == null ? null
				: jdbc.sql("SELECT validity_days FROM makeup_policy WHERE id = :id").param("id", product.policyId())
					.query(Integer.class).optional().orElse(null);
		return days == null || days <= 0 ? DisputeTerms.DEFAULT_MAKEUP_VALIDITY_DAYS : days;
	}

	private static DisputeOption makeupOption(Integer days) {
		return new DisputeOption(days != null, DisputeEffect.MAKEUP_GRANTED, days == null ? "MAKEUP_NOT_USABLE" : null);
	}

	/** A returned entry still goes back to a pass that cannot be used; the administrator is told so. */
	private String notice(DisputedPass pass) {
		if (pass.status() == CustomerPassStatus.CANCELLED) {
			return "PASS_CANCELLED";
		}
		boolean expired = pass.status() == CustomerPassStatus.EXPIRED
				|| pass.validUntil() != null && pass.validUntil().isBefore(studio.today());
		return expired ? "PASS_EXPIRED" : null;
	}

	private DisputedPass disputedPass(UUID customerId, UUID passId) {
		return jdbc.sql("""
				SELECT variant_id, product_name, entries_remaining, status, valid_until
				FROM customer_pass WHERE id = :id AND customer_id = :customer
				""").param("id", passId).param("customer", customerId)
			.query((rs, n) -> new DisputedPass(rs.getObject("variant_id", UUID.class), rs.getString("product_name"),
					rs.getObject("entries_remaining", Short.class), CustomerPassStatus.valueOf(rs.getString("status")),
					rs.getObject("valid_until", LocalDate.class)))
			.optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass not found."));
	}

	private Credit credit(UUID customerId, UUID creditId, boolean lock) {
		return jdbc.sql("""
				SELECT c.status, c.used_reservation_id, c.expires_at, c.source_pass_id, p.product_name, p.variant_id,
				       p.entries_total IS NOT NULL AS finite
				FROM makeup_credit c JOIN customer_pass p ON p.id = c.source_pass_id
				WHERE c.id = :id AND c.customer_id = :customer AND p.customer_id = :customer
				""" + (lock ? " FOR UPDATE OF c" : ""))
			.param("id", creditId).param("customer", customerId)
			.query((rs, n) -> new Credit(rs.getString("status"), rs.getObject("used_reservation_id", UUID.class),
					rs.getObject("expires_at", OffsetDateTime.class).toInstant(), rs.getString("product_name"),
					rs.getObject("source_pass_id", UUID.class), rs.getObject("variant_id", UUID.class),
					rs.getBoolean("finite")))
			.optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Make-up credit not found."));
	}

	private record Pass(UUID variantId, String name, boolean finite) {}
	private record DisputedPass(UUID variantId, String name, Short remaining, CustomerPassStatus status,
			LocalDate validUntil) {}
	private record Policy(int days, Integer limit, boolean grant, boolean active) {}
	private record Credit(String status, UUID reservationId, Instant expiresAt, String passName, UUID sourcePassId,
			UUID variantId, boolean finite) {}
}
