package studioforyou.pl.backend.membership.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.MakeupEntitlements;
import studioforyou.pl.backend.offering.PassMakeupCoverage;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

@Service
@Transactional(readOnly = true)
class DefaultMakeupEntitlements implements MakeupEntitlements {

	private static final String SELECT = """
			SELECT credit.id, credit.status, credit.expires_at, credit.used_at, credit.used_reservation_id,
			       pass.variant_id, pass.product_name, pass.entries_total IS NOT NULL AS finite
			FROM makeup_credit credit JOIN customer_pass pass ON pass.id = credit.source_pass_id
			WHERE credit.id = :id AND credit.customer_id = :profile AND pass.customer_id = :profile
			""";

	private final JdbcClient jdbc;
	private final PassMakeupCoverage coverage;
	private final Clock clock = Clock.systemUTC();

	DefaultMakeupEntitlements(JdbcClient jdbc, PassMakeupCoverage coverage) {
		this.jdbc = jdbc;
		this.coverage = coverage;
	}

	@Override
	public Credit available(UUID profileId, UUID creditId) {
		return usable(read(profileId, creditId, false), clock.instant());
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void reserve(UUID profileId, UUID creditId, UUID serviceTypeId, Instant startsAt, UUID reservationId) {
		Row row = read(profileId, creditId, true);
		if ("USED".equals(row.status()) && reservationId.equals(row.usedReservationId())) {
			return;
		}
		Credit credit = usable(row, clock.instant());
		if (!credit.covers(serviceTypeId, startsAt)) {
			throw new ApiException(ErrorCode.MAKEUP_CREDIT_NOT_USABLE, "The credit does not cover this occurrence.");
		}
		jdbc.sql("UPDATE makeup_credit SET status = 'USED', used_at = clock_timestamp(), used_reservation_id = :reservation WHERE id = :id")
			.param("id", creditId).param("reservation", reservationId).update();
	}

	@Override
	public boolean canReturn(UUID profileId, UUID creditId, UUID reservationId, Instant now) {
		return restorable(read(profileId, creditId, false), reservationId, now);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(UUID profileId, UUID creditId, UUID reservationId, boolean policyReturns, boolean expectedReturn) {
		Row row = read(profileId, creditId, true);
		boolean returns = policyReturns && restorable(row, reservationId, clock.instant());
		if (returns != expectedReturn) {
			throw new ApiException(ErrorCode.CANCELLATION_CONSEQUENCE_CHANGED,
					"The credit return consequence has changed. Confirm it again.", Map.of("entryReturned", returns));
		}
		if (returns) {
			jdbc.sql("UPDATE makeup_credit SET status = 'AVAILABLE', used_at = NULL, used_reservation_id = NULL WHERE id = :id")
				.param("id", creditId).update();
		}
	}

	private Credit usable(Row row, Instant now) {
		if (!"AVAILABLE".equals(row.status()) || row.usedAt() != null || row.usedReservationId() != null
				|| !row.expiresAt().isAfter(now)) {
			throw new ApiException(ErrorCode.MAKEUP_CREDIT_NOT_AVAILABLE, "The credit is no longer available.");
		}
		var terms = coverage.find(row.variantId()).orElseThrow(DefaultMakeupEntitlements::unusable);
		if (!row.finite() || "TIME_PASS".equals(terms.kind()) || "VOUCHER".equals(terms.kind())) {
			throw unusable();
		}
		String expiryCheck = terms.policyId() == null ? "EVENT_DATE" : jdbc.sql("SELECT expiry_checked_against FROM makeup_policy WHERE id = :id")
			.param("id", terms.policyId()).query(String.class).optional().orElse("EVENT_DATE");
		return new Credit(row.id(), row.productName(), row.expiresAt(), terms.serviceTypeIds(), !"BOOK_DATE".equals(expiryCheck));
	}

	private Row read(UUID profileId, UUID creditId, boolean lock) {
		return jdbc.sql(SELECT + (lock ? " FOR UPDATE OF credit" : ""))
			.param("id", creditId).param("profile", profileId).query((row, number) -> new Row(
					row.getObject("id", UUID.class), row.getString("status"), row.getObject("expires_at", OffsetDateTime.class).toInstant(),
					row.getObject("used_at", OffsetDateTime.class), row.getObject("used_reservation_id", UUID.class),
					row.getObject("variant_id", UUID.class), row.getString("product_name"), row.getBoolean("finite")))
			.optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Credit not found."));
	}

	private static boolean restorable(Row row, UUID reservationId, Instant now) {
		return "USED".equals(row.status()) && reservationId.equals(row.usedReservationId()) && row.usedAt() != null && row.expiresAt().isAfter(now);
	}

	private static ApiException unusable() {
		return new ApiException(ErrorCode.MAKEUP_CREDIT_NOT_USABLE, "The source pass has no applicable make-up coverage.");
	}

	private record Row(UUID id, String status, Instant expiresAt, OffsetDateTime usedAt, UUID usedReservationId,
			UUID variantId, String productName, boolean finite) {
	}
}
