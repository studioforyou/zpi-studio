package studioforyou.pl.backend.membership;

/**
 * Status of a participant's pass ({@code customer_pass.status}).
 */
public enum CustomerPassStatus {

	/** Ordered, waiting for the payment; not usable yet. */
	PENDING_PAYMENT,
	/** Assigned or bought before its start date; becomes active on {@code valid_from}. */
	SCHEDULED,
	/** Usable within its validity. */
	ACTIVE,
	/** Temporarily suspended. */
	SUSPENDED,
	/** Past its validity. */
	EXPIRED,
	/** Cancelled, e.g. assigned by mistake. */
	CANCELLED

}
