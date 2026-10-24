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
 * A service type that may take place in the room. Whether a given event blocks the whole room is
 * decided for that event ({@code event_resource.blocks_whole_room}).
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
