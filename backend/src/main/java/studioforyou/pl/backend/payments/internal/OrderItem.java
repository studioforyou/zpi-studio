package studioforyou.pl.backend.payments.internal;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An order line with a copy of the product's name and price at the time of the order. The
 * beneficiary is the participant the pass is for and may differ from the buyer.
 */
@Entity
@Table(name = "order_item")
class OrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "order_id", updatable = false)
	private UUID orderId;

	@Column(name = "variant_id", updatable = false)
	private UUID variantId;

	@Column(name = "beneficiary_id", updatable = false)
	private UUID beneficiaryId;

	@Column(name = "product_name", updatable = false)
	private String productName;

	@Column(name = "unit_price", precision = 10, scale = 2, updatable = false)
	private BigDecimal unitPrice;

	@Column(updatable = false)
	private short quantity;

	protected OrderItem() {
	}

	OrderItem(UUID orderId, UUID variantId, UUID beneficiaryId, String productName, BigDecimal unitPrice) {
		this.orderId = orderId;
		this.variantId = variantId;
		this.beneficiaryId = beneficiaryId;
		this.productName = productName;
		this.unitPrice = unitPrice;
		this.quantity = 1;
	}

	UUID getId() {
		return this.id;
	}

	UUID getOrderId() {
		return this.orderId;
	}

	UUID getVariantId() {
		return this.variantId;
	}

	UUID getBeneficiaryId() {
		return this.beneficiaryId;
	}

	String getProductName() {
		return this.productName;
	}

	BigDecimal getUnitPrice() {
		return this.unitPrice;
	}

	short getQuantity() {
		return this.quantity;
	}

}
