package studioforyou.pl.backend.payments.internal;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One attempt to pay for an order.
 */
@Entity
@Table(name = "payment")
class Payment {

	/** The method of a {@link PaymentProvider#MOCK} payment. */
	static final String MOCK_METHOD = "MOCK";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "order_id", updatable = false)
	private UUID orderId;

	@Enumerated(EnumType.STRING)
	@Column(updatable = false)
	private PaymentProvider provider;

	@Column(updatable = false)
	private String method;

	@Enumerated(EnumType.STRING)
	private PaymentStatus status;

	@Column(precision = 10, scale = 2, updatable = false)
	private BigDecimal amount;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3, updatable = false)
	private String currency;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	protected Payment() {
	}

	/**
	 * A simulated payment of the whole order, confirmed at once.
	 */
	static Payment confirmedMock(CustomerOrder order, Instant confirmedAt) {
		Payment payment = new Payment();
		payment.orderId = order.getId();
		payment.provider = PaymentProvider.MOCK;
		payment.method = MOCK_METHOD;
		payment.status = PaymentStatus.CONFIRMED;
		payment.amount = order.getTotalAmount();
		payment.currency = order.getCurrency();
		payment.confirmedAt = confirmedAt;
		return payment;
	}

	UUID getId() {
		return this.id;
	}

	PaymentProvider getProvider() {
		return this.provider;
	}

	PaymentStatus getStatus() {
		return this.status;
	}

}
