package studioforyou.pl.backend.payments.internal;

/**
 * Status of an order ({@code customer_order.status}).
 */
enum OrderStatus {

	/** Placed, waiting for the payment. */
	CREATED,
	/** Paid; its passes are activated. */
	PAID,
	CANCELLED

}
