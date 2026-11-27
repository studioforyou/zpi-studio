package studioforyou.pl.backend.membership.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.membership.PassChangeHistory;

/** Reads only the pass owner's tables; the caller describes the reservations. */
@Service
@Transactional(readOnly = true)
class DefaultPassChangeHistory implements PassChangeHistory {

	/** The note {@code CustomerPassService.cancelSuspension} saves; it is not the staff's own words. */
	static final String SUSPENSION_CANCELLED_NOTE = "Suspension cancelled";

	private final JdbcClient jdbc;

	DefaultPassChangeHistory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<History> find(UUID customerId, UUID passId) {
		Optional<PassRow> found = jdbc.sql("""
				SELECT id, customer_id, product_name, entries_total, entries_remaining,
				       order_item_id IS NOT NULL AS purchased, renewal_declined_at
				FROM customer_pass WHERE id = :pass AND customer_id = :customer
				""")
			.param("pass", passId)
			.param("customer", customerId)
			.query((rs, row) -> new PassRow(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
					rs.getString("product_name"), smallint(rs, "entries_total"), smallint(rs, "entries_remaining"),
					rs.getBoolean("purchased"), instant(rs, "renewal_declined_at")))
			.optional();
		if (found.isEmpty()) {
			return Optional.empty();
		}
		PassRow pass = found.get();
		boolean finite = pass.entriesTotal() != null;

		List<Change> changes = new ArrayList<>();
		int balance = 0;
		for (LedgerRow row : ledger(passId)) {
			balance += row.delta();
			Kind kind = kind(row);
			if (kind == null) {
				continue;
			}
			boolean balanceKind = switch (kind) {
				case ACTIVATED, ENTRY_RESERVED, ENTRY_USED, ENTRY_RETURNED, CORRECTED, DISPUTE_REFUND -> true;
				default -> false;
			};
			boolean showsChange = finite && (balanceKind || row.delta() != 0);
			// The cancellation's note is a fixed technical text, not one the staff wrote.
			String reason = (kind == Kind.SUSPENSION_CANCELLED && SUSPENSION_CANCELLED_NOTE.equals(row.reason())) ? null
					: row.reason();
			changes.add(new Change(row.id(), kind, row.occurredAt(), row.actorAccountId(), reason,
					showsChange ? row.delta() : null, showsChange ? balance : null, row.validUntilBefore(),
					row.validUntilAfter(), row.reservationId(), period(kind, row), null));
		}
		Instant now = Instant.now();
		jdbc.sql("""
				SELECT id, source_reservation_id, granted_by, granted_at, expires_at, status, used_reservation_id, used_at
				FROM makeup_credit WHERE source_pass_id = :pass
				ORDER BY granted_at, id
				""")
			.param("pass", passId)
			.query((rs, row) -> {
				UUID used = rs.getObject("used_reservation_id", UUID.class);
				Instant expiresAt = instant(rs, "expires_at");
				String status = rs.getString("status");
				MakeupCreditState state = ("USED".equals(status) || used != null) ? MakeupCreditState.USED
						: ("AVAILABLE".equals(status) && expiresAt.isAfter(now)) ? MakeupCreditState.AVAILABLE
								: MakeupCreditState.EXPIRED;
				return new Change(rs.getObject("id", UUID.class), Kind.MAKEUP_GRANTED, instant(rs, "granted_at"),
						rs.getObject("granted_by", UUID.class), null, null, null, null, null,
						rs.getObject("source_reservation_id", UUID.class), null,
						new MakeupCredit(rs.getObject("id", UUID.class), expiresAt, state, instant(rs, "used_at"), used));
			})
			.list()
			.forEach(changes::add);
		if (pass.renewalDeclinedAt() != null) {
			// Only the participant or a guardian declines, and who it was is not recorded.
			changes.add(new Change(pass.id(), Kind.RENEWAL_DECLINED, pass.renewalDeclinedAt(), null, null, null, null,
					null, null, null, null, null));
		}
		// A stable sort keeps the ledger's own order for changes made at the same moment.
		changes.sort(Comparator.comparing(Change::occurredAt));
		return Optional.of(new History(pass.id(), pass.customerId(), pass.productName(), pass.entriesTotal(),
				pass.entriesRemaining(), finite ? balance : null, pass.purchased(), List.copyOf(changes)));
	}

	private List<LedgerRow> ledger(UUID passId) {
		return jdbc.sql("""
				SELECT ledger.id, ledger.kind, ledger.entries_delta, ledger.valid_until_before, ledger.valid_until_after,
				       ledger.reservation_id, ledger.reason, ledger.actor_account_id, ledger.occurred_at,
				       suspension.starts_on, suspension.ends_on, suspension.cancelled_at IS NOT NULL AS whole_cancelled
				FROM pass_ledger ledger
				LEFT JOIN pass_suspension suspension ON suspension.id = ledger.suspension_id
				WHERE ledger.pass_id = :pass
				ORDER BY ledger.occurred_at, ledger.id
				""")
			.param("pass", passId)
			.query((rs, row) -> new LedgerRow(rs.getObject("id", UUID.class), rs.getString("kind"),
					rs.getInt("entries_delta"), rs.getObject("valid_until_before", LocalDate.class),
					rs.getObject("valid_until_after", LocalDate.class), rs.getObject("reservation_id", UUID.class),
					rs.getString("reason"), rs.getObject("actor_account_id", UUID.class), instant(rs, "occurred_at"),
					rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
					rs.getBoolean("whole_cancelled")))
			.list();
	}

	private static @Nullable Kind kind(LedgerRow row) {
		return switch (row.kind()) {
			case "ACTIVATION" -> Kind.ACTIVATED;
			case "ENTRY_RESERVED" -> Kind.ENTRY_RESERVED;
			case "ENTRY_CONSUMED" -> Kind.ENTRY_USED;
			case "ENTRY_RETURNED" -> Kind.ENTRY_RETURNED;
			// Only a refund after an upheld dispute links a correction to a reservation.
			case "CORRECTION" -> (row.reservationId() == null) ? Kind.CORRECTED : Kind.DISPUTE_REFUND;
			case "EXTENSION" -> extensionKind(row);
			case "EXPIRATION" -> Kind.EXPIRED;
			default -> null;
		};
	}

	private static Kind extensionKind(LedgerRow row) {
		boolean earlier = row.validUntilBefore() != null && row.validUntilAfter() != null
				&& row.validUntilAfter().isBefore(row.validUntilBefore());
		if (row.suspensionStartsOn() != null) {
			return earlier ? Kind.SUSPENSION_CANCELLED : Kind.SUSPENDED;
		}
		// Entries saved before they were linked to their suspension.
		return (earlier && SUSPENSION_CANCELLED_NOTE.equals(row.reason())) ? Kind.SUSPENSION_CANCELLED : Kind.EXTENDED;
	}

	/**
	 * The suspended days, or the days a cancellation gave back. The entry's own change of the last
	 * day gives the number of days, since the suspension's end moves when it is cut short.
	 */
	private static @Nullable Period period(Kind kind, LedgerRow row) {
		LocalDate startsOn = row.suspensionStartsOn();
		if (startsOn == null || (kind != Kind.SUSPENDED && kind != Kind.SUSPENSION_CANCELLED)) {
			return null;
		}
		if (row.validUntilBefore() == null || row.validUntilAfter() == null) {
			return new Period(startsOn, row.suspensionEndsOn());
		}
		long days = Math.abs(ChronoUnit.DAYS.between(row.validUntilBefore(), row.validUntilAfter()));
		if (days == 0) {
			return new Period(startsOn, row.suspensionEndsOn());
		}
		if (kind == Kind.SUSPENDED || row.wholeCancelled()) {
			return new Period(startsOn, startsOn.plusDays(days - 1));
		}
		// Cut short while in progress: it now ends the day before the cancellation.
		return new Period(row.suspensionEndsOn().plusDays(1), row.suspensionEndsOn().plusDays(days));
	}

	private static @Nullable Short smallint(ResultSet rs, String column) throws SQLException {
		short value = rs.getShort(column);
		return rs.wasNull() ? null : value;
	}

	private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return (value == null) ? null : value.toInstant();
	}

	private record PassRow(UUID id, UUID customerId, String productName, @Nullable Short entriesTotal,
			@Nullable Short entriesRemaining, boolean purchased, @Nullable Instant renewalDeclinedAt) {
	}

	private record LedgerRow(UUID id, String kind, int delta, @Nullable LocalDate validUntilBefore,
			@Nullable LocalDate validUntilAfter, @Nullable UUID reservationId, @Nullable String reason,
			@Nullable UUID actorAccountId, Instant occurredAt, @Nullable LocalDate suspensionStartsOn,
			@Nullable LocalDate suspensionEndsOn, boolean wholeCancelled) {
	}

}
