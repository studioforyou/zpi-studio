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
 * An order: who buys and for how much. The buyer is a customer profile, e.g. a parent buying
 * a pass for a child.
 */
@Entity
@Table(name = "customer_order")
class CustomerOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "buyer_id", updatable = false)
	private UUID buyerId;

	@Enumerated(EnumType.STRING)
	private OrderStatus status;

	@Column(name = "total_amount", precision = 10, scale = 2, updatable = false)
	private BigDecimal totalAmount;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3, updatable = false)
	private String currency;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "paid_at")
	private Instant paidAt;

	protected CustomerOrder() {
	}

	CustomerOrder(UUID buyerId, BigDecimal totalAmount, String currency, UUID createdBy) {
		this.buyerId = buyerId;
		this.status = OrderStatus.CREATED;
		this.totalAmount = totalAmount;
		this.currency = currency;
		this.createdBy = createdBy;
	}

	void markPaid(Instant paidAt) {
		this.status = OrderStatus.PAID;
		this.paidAt = paidAt;
	}

	UUID getId() {
		return this.id;
	}

	UUID getBuyerId() {
		return this.buyerId;
	}

	OrderStatus getStatus() {
		return this.status;
	}

	BigDecimal getTotalAmount() {
		return this.totalAmount;
	}

	String getCurrency() {
		return this.currency;
	}

	UUID getCreatedBy() {
		return this.createdBy;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getPaidAt() {
		return this.paidAt;
	}

}
