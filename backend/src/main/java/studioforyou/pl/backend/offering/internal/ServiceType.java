package studioforyou.pl.backend.offering.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of a service type. Managing service types is a separate feature.
 */
@Entity
@Immutable
@Table(name = "service_type")
public class ServiceType {

	@Id
	private UUID id;

	private String name;

	private String category;

	private String level;

	@Column(name = "is_active")
	private boolean active;

	protected ServiceType() {
	}

	public UUID getId() {
		return this.id;
	}

	public String getName() {
		return this.name;
	}

	public String getCategory() {
		return this.category;
	}

	public String getLevel() {
		return this.level;
	}

	public boolean isActive() {
		return this.active;
	}

}
