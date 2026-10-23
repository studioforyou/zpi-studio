package studioforyou.pl.backend.membership.internal;

/**
 * Kind of a pass ledger entry ({@code pass_ledger.kind}).
 */
enum PassLedgerKind {

	/** The pass was activated with its entries and validity. */
	ACTIVATION,
	ENTRY_RESERVED,
	ENTRY_CONSUMED,
	ENTRY_RETURNED,
	/** A manual change of the balance. */
	CORRECTION,
	/** A change of the validity, e.g. after a suspension. */
	EXTENSION,
	EXPIRATION

}
