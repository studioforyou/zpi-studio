package studioforyou.pl.backend.offering.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import studioforyou.pl.backend.offering.PassProductAudience;
import studioforyou.pl.backend.offering.PassProductKind;

/**
 * A pass product with its price variants and the services it pays for. Products are
 * archived ({@code is_active = false}), never deleted. The make-up policy is managed by
 * the membership module and is not mapped here.
 */
@Entity
@Table(name = "pass_product")
public class PassProduct {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	private PassProductKind kind;

	@Enumerated(EnumType.STRING)
	private PassProductAudience audience;

	@Column(name = "suspension_allowed")
	private boolean suspensionAllowed;

	@Column(name = "max_suspension_days")
	private Short maxSuspensionDays;

	@Column(name = "is_active")
	private boolean active = true;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL)
	@OrderBy("validFrom, entriesCount, validityDays")
	private List<PassProductVariant> variants = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "pass_product_service", joinColumns = @JoinColumn(name = "product_id"))
	@Column(name = "service_type_id")
	private Set<UUID> serviceTypeIds = new HashSet<>();

	protected PassProduct() {
	}

	public PassProduct(String name, String description, PassProductKind kind, PassProductAudience audience) {
		this.name = name;
		this.description = description;
		this.kind = kind;
		this.audience = audience;
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public PassProductKind getKind() {
		return this.kind;
	}

	public PassProductAudience getAudience() {
		return this.audience;
	}

	public void setAudience(PassProductAudience audience) {
		this.audience = audience;
	}

	public boolean isSuspensionAllowed() {
		return this.suspensionAllowed;
	}

	public Short getMaxSuspensionDays() {
		return this.maxSuspensionDays;
	}

	/**
	 * Sets whether the pass can be suspended; without suspension there is no day limit.
	 */
	public void setSuspension(boolean allowed, Short maxDays) {
		this.suspensionAllowed = allowed;
		this.maxSuspensionDays = allowed ? maxDays : null;
	}

	public boolean isActive() {
		return this.active;
	}

	public void setActive(boolean active) {
		this.active = active;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public List<PassProductVariant> getVariants() {
		return this.variants;
	}

	public void addVariant(PassProductVariant variant) {
		this.variants.add(variant);
	}

	public Set<UUID> getServiceTypeIds() {
		return this.serviceTypeIds;
	}

	public void setServiceTypeIds(Collection<UUID> serviceTypeIds) {
		this.serviceTypeIds.clear();
		this.serviceTypeIds.addAll(serviceTypeIds);
	}

}
