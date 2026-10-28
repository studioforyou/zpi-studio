package studioforyou.pl.backend.membership.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

/**
 * One event in the history of a pass: activation, entry use, correction, extension. Entries
 * are only ever added, never changed or removed.
 */
@Entity
@Immutable
@Table(name = "pass_ledger")
class PassLedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "pass_id")
	private UUID passId;

	@Enumerated(EnumType.STRING)
	private PassLedgerKind kind;

	@Column(name = "entries_delta")
	private short entriesDelta;

	@Column(name = "valid_until_before")
	private LocalDate validUntilBefore;

	@Column(name = "valid_until_after")
	private LocalDate validUntilAfter;

	@Column(name = "reservation_id")
	private UUID reservationId;

	private String reason;

	@Column(name = "actor_account_id")
	private UUID actorAccountId;

	@CreationTimestamp
	@Column(name = "occurred_at")
	private Instant occurredAt;

	protected PassLedgerEntry() {
	}

	private PassLedgerEntry(UUID passId, PassLedgerKind kind, short entriesDelta, LocalDate validUntilBefore,
			LocalDate validUntilAfter, String reason, UUID actorAccountId) {
		this.passId = passId;
		this.kind = kind;
		this.entriesDelta = entriesDelta;
		this.validUntilBefore = validUntilBefore;
		this.validUntilAfter = validUntilAfter;
		this.reason = reason;
		this.actorAccountId = actorAccountId;
	}

	/** Activation of a new pass: all its entries (0 for unlimited entries) and its validity. */
	static PassLedgerEntry activation(CustomerPass pass, String reason, UUID actorAccountId) {
		short entries = (pass.getEntriesTotal() != null) ? pass.getEntriesTotal() : 0;
		return new PassLedgerEntry(pass.getId(), PassLedgerKind.ACTIVATION, entries, null, pass.getValidUntil(),
				reason, actorAccountId);
	}

}
