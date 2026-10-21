package studioforyou.pl.backend.offering.internal;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import studioforyou.pl.backend.offering.ServiceTypeSummary;

// Read-only for now: managing the service catalogue belongs to a separate offering task.
@Entity
@Immutable
@Table(name = "service_type")
class ServiceType {

	@Id
	private UUID id;

	private String name;

	@Column(name = "is_active")
	private boolean active;

	protected ServiceType() {
	}

	ServiceTypeSummary toSummary() {
		return new ServiceTypeSummary(this.id, this.name, this.active);
	}

}
