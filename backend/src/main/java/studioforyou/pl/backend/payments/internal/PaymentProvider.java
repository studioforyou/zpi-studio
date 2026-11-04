package studioforyou.pl.backend.payments.internal;

/**
 * Who handled a payment ({@code payment.provider}).
 */
enum PaymentProvider {

	PRZELEWY24,
	/** Cash or a bank transfer registered by the staff. */
	MANUAL,
	/**
	 * A simulated online payment, confirmed as soon as the client pays, until a real payment
	 * provider is connected.
	 */
	MOCK

}
