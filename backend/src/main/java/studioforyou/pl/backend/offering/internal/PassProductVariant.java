package studioforyou.pl.backend.offering.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One price variant of a product: number of entries x validity x price. A variant's terms
 * never change; a new price is a new variant and the old one is deactivated.
 */
@Entity
@Table(name = "pass_product_variant")
public class PassProductVariant {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id")
	private PassProduct product;

	@Column(name = "entries_count")
	private Short entriesCount;

	@Column(name = "validity_days")
	private short validityDays;

	@Column(precision = 10, scale = 2)
	private BigDecimal price;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	@Column(name = "is_active")
	private boolean active = true;

	@Column(name = "valid_from")
	private Instant validFrom;

	@Column(name = "valid_to")
	private Instant validTo;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	protected PassProductVariant() {
	}

	/**
	 * @param entriesCount number of entries, or {@code null} for unlimited entries
	 */
	public PassProductVariant(PassProduct product, Short entriesCount, short validityDays, BigDecimal price,
			String currency, Instant validFrom) {
		this.product = product;
		this.entriesCount = entriesCount;
		this.validityDays = validityDays;
		this.price = price;
		this.currency = currency;
		this.validFrom = validFrom;
	}

	public UUID getId() {
		return this.id;
	}

	public PassProduct getProduct() {
		return this.product;
	}

	public Short getEntriesCount() {
		return this.entriesCount;
	}

	public short getValidityDays() {
		return this.validityDays;
	}

	public BigDecimal getPrice() {
		return this.price;
	}

	public String getCurrency() {
		return this.currency;
	}

	public boolean isActive() {
		return this.active;
	}

	public Instant getValidFrom() {
		return this.validFrom;
	}

	public Instant getValidTo() {
		return this.validTo;
	}

	/** Whether this variant has the same entries and validity, which must be unique among active variants. */
	public boolean hasSameTerms(Short entriesCount, short validityDays) {
		return Objects.equals(this.entriesCount, entriesCount) && this.validityDays == validityDays;
	}

	public void deactivate(Instant at) {
		if (this.active) {
			this.active = false;
			this.validTo = at;
		}
	}

}
