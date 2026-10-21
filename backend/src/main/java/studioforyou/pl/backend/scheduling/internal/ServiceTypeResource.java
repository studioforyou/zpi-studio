package studioforyou.pl.backend.scheduling.internal;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Sharing rule of a room: a service type that may take place in the room.
 *
 * <p>TODO(scheduling): the table also has {@code units_required} (capacity units the service takes,
 * NULL = the whole room), which is deliberately not mapped and stays NULL. How much of a room an
 * event takes is chosen when the event is planned ({@code event_resource.units_used}, #131). Decide
 * whether the column should become a default for that choice or be dropped in a migration.
 */
@Entity
@Table(name = "service_type_resource")
class ServiceTypeResource implements Persistable<ServiceTypeResource.Key> {

	@EmbeddedId
	private Key key;

	@Column(name = "is_default")
	private boolean defaultResource;

	// The key is assigned, so a new rule must be inserted, never merged over an existing one.
	@Transient
	private boolean isNew;

	protected ServiceTypeResource() {
	}

	ServiceTypeResource(UUID resourceId, UUID serviceTypeId, boolean defaultResource) {
		this.key = new Key(serviceTypeId, resourceId);
		this.defaultResource = defaultResource;
		this.isNew = true;
	}

	@Override
	public Key getId() {
		return this.key;
	}

	@Override
	public boolean isNew() {
		return this.isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}

	UUID getResourceId() {
		return this.key.resourceId;
	}

	UUID getServiceTypeId() {
		return this.key.serviceTypeId;
	}

	boolean isDefaultResource() {
		return this.defaultResource;
	}

	void setDefaultResource(boolean defaultResource) {
		this.defaultResource = defaultResource;
	}

	@Embeddable
	record Key(@Column(name = "service_type_id") UUID serviceTypeId,
			@Column(name = "resource_id") UUID resourceId) implements Serializable {
	}

}
