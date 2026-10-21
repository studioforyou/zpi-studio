package studioforyou.pl.backend.scheduling.internal;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A room that events take place in, with its capacity.
 */
@Entity
@Table(name = "resource")
class Resource {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String name;

	@Enumerated(EnumType.STRING)
	private ResourceKind kind;

	@Column(name = "parent_resource_id")
	private UUID parentResourceId;

	@Column(name = "capacity_units")
	private short capacityUnits;

	@Column(name = "max_people")
	private Short maxPeople;

	@Column(columnDefinition = "text")
	private String description;

	@Column(name = "is_active")
	private boolean active;

	@CreationTimestamp
	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;

	protected Resource() {
	}

	Resource(String name, ResourceKind kind, short capacityUnits) {
		this.name = name;
		this.kind = kind;
		this.capacityUnits = capacityUnits;
		this.active = true;
	}

	UUID getId() {
		return this.id;
	}

	String getName() {
		return this.name;
	}

	void setName(String name) {
		this.name = name;
	}

	ResourceKind getKind() {
		return this.kind;
	}

	void setKind(ResourceKind kind) {
		this.kind = kind;
	}

	UUID getParentResourceId() {
		return this.parentResourceId;
	}

	void setParentResourceId(UUID parentResourceId) {
		this.parentResourceId = parentResourceId;
	}

	short getCapacityUnits() {
		return this.capacityUnits;
	}

	void setCapacityUnits(short capacityUnits) {
		this.capacityUnits = capacityUnits;
	}

	Short getMaxPeople() {
		return this.maxPeople;
	}

	void setMaxPeople(Short maxPeople) {
		this.maxPeople = maxPeople;
	}

	String getDescription() {
		return this.description;
	}

	void setDescription(String description) {
		this.description = description;
	}

	boolean isActive() {
		return this.active;
	}

	void setActive(boolean active) {
		this.active = active;
	}

	Instant getCreatedAt() {
		return this.createdAt;
	}

	Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
