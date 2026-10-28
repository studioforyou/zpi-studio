package studioforyou.pl.backend.membership.internal;

import java.math.BigDecimal;
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
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import studioforyou.pl.backend.membership.CustomerPassStatus;

/**
 * A pass held by one participant, with a copy of the terms it was assigned or bought with:
 * later changes to the offer do not change it. The participant and the variant belong to
 * other modules and are referenced by ID only.
 */
@Entity
@Table(name = "customer_pass")
public class CustomerPass {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "customer_id", updatable = false)
	private UUID customerId;

	@Column(name = "variant_id", updatable = false)
	private UUID variantId;

	@Column(name = "order_item_id", updatable = false)
	private UUID orderItemId;

	@Column(name = "product_name")
	private String productName;

	@Column(name = "entries_total")
	private Short entriesTotal;

	@Column(name = "entries_remaining")
	private Short entriesRemaining;

	@Column(name = "price_paid", precision = 10, scale = 2)
	private BigDecimal pricePaid;

	@Enumerated(EnumType.STRING)
	private CustomerPassStatus status;

	@Column(name = "valid_from")
	private LocalDate validFrom;

	@Column(name = "valid_until")
	private LocalDate validUntil;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	@Version
	private int version;

	protected CustomerPass() {
	}

	/**
	 * A pass assigned by the studio staff, outside of an order.
	 *
	 * @param entries number of entries, or {@code null} for unlimited entries
	 */
	CustomerPass(UUID customerId, UUID variantId, String productName, Short entries, BigDecimal pricePaid,
			CustomerPassStatus status, LocalDate validFrom, LocalDate validUntil, UUID createdBy) {
		this.customerId = customerId;
		this.variantId = variantId;
		this.productName = productName;
		this.entriesTotal = entries;
		this.entriesRemaining = entries;
		this.pricePaid = pricePaid;
		this.status = status;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.createdBy = createdBy;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getCustomerId() {
		return this.customerId;
	}

	public UUID getVariantId() {
		return this.variantId;
	}

	/** The order line the pass was bought with, or {@code null} if the staff assigned it. */
	public UUID getOrderItemId() {
		return this.orderItemId;
	}

	public String getProductName() {
		return this.productName;
	}

	/** Number of entries, or {@code null} for unlimited entries. */
	public Short getEntriesTotal() {
		return this.entriesTotal;
	}

	/** Entries left, or {@code null} for unlimited entries. */
	public Short getEntriesRemaining() {
		return this.entriesRemaining;
	}

	public BigDecimal getPricePaid() {
		return this.pricePaid;
	}

	public CustomerPassStatus getStatus() {
		return this.status;
	}

	/**
	 * The status on the given day. Nothing updates the stored status when the dates pass yet,
	 * so a scheduled pass counts as active from its first day and an active or scheduled pass
	 * counts as expired after its last day.
	 */
	public CustomerPassStatus statusOn(LocalDate day) {
		boolean running = this.status == CustomerPassStatus.ACTIVE || this.status == CustomerPassStatus.SCHEDULED;
		if (!running) {
			return this.status;
		}
		if (this.validUntil != null && day.isAfter(this.validUntil)) {
			return CustomerPassStatus.EXPIRED;
		}
		if (this.validFrom == null) {
			return this.status;
		}
		return day.isBefore(this.validFrom) ? CustomerPassStatus.SCHEDULED : CustomerPassStatus.ACTIVE;
	}

	public LocalDate getValidFrom() {
		return this.validFrom;
	}

	/** The last day the pass is valid, inclusive. */
	public LocalDate getValidUntil() {
		return this.validUntil;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public int getVersion() {
		return this.version;
	}

}
