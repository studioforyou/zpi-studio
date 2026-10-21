package studioforyou.pl.backend.scheduling.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request and response bodies of the room endpoints.
 *
 * <p>Update requests are partial: a missing field is left unchanged. Fields that can be cleared are
 * {@link Optional}: an explicit {@code null} clears the value.
 */
final class ResourceDtos {

	static final int MAX_NAME_LENGTH = 80;

	private ResourceDtos() {
	}

	@Schema(name = "CreateResourceRequest")
	record CreateResourceRequest(
			@NotBlank @Size(max = MAX_NAME_LENGTH) @Schema(example = "Sala pole dance") String name,
			@NotNull ResourceKind kind,
			@Schema(description = "Room this one is a part of; null for a top-level room.") UUID parentResourceId,
			@NotNull @Min(1) @Max(Short.MAX_VALUE)
			@Schema(description = "How many events (e.g. personal trainings) can use the room at the same time.", example = "3")
			Integer capacityUnits,
			@Min(1) @Max(Short.MAX_VALUE)
			@Schema(description = "Maximum number of people in group classes in this room.", example = "12")
			Integer maxPeople,
			String description,
			@Schema(description = "Service types allowed to use the room; none when missing.")
			List<@Valid ServiceTypeAssignment> serviceTypes) {
	}

	// Update requests are classes, not records: Jackson calls a setter only for a field present in
	// the body, while a missing record component would become Optional.empty() like an explicit null.
	@Schema(name = "UpdateResourceRequest", description = "Partial update; missing fields stay unchanged.")
	static final class UpdateResourceRequest {

		@Size(max = MAX_NAME_LENGTH)
		@Pattern(regexp = ".*\\S.*", message = "must not be blank")
		private String name;

		private ResourceKind kind;

		@Schema(description = "null makes the room top-level.", types = { "string", "null" }, format = "uuid")
		private Optional<UUID> parentResourceId;

		@Min(1)
		@Max(Short.MAX_VALUE)
		private Integer capacityUnits;

		@Schema(description = "null removes the limit.", types = { "integer", "null" })
		private Optional<@Min(1) @Max(Short.MAX_VALUE) Integer> maxPeople;

		@Schema(types = { "string", "null" })
		private Optional<String> description;

		@Schema(description = "false deactivates the room; it is never deleted.")
		private Boolean isActive;

		@Schema(description = "Replaces the whole set of service types allowed to use the room.")
		private List<@Valid ServiceTypeAssignment> serviceTypes;

		public String getName() {
			return this.name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public ResourceKind getKind() {
			return this.kind;
		}

		public void setKind(ResourceKind kind) {
			this.kind = kind;
		}

		public Optional<UUID> getParentResourceId() {
			return this.parentResourceId;
		}

		public void setParentResourceId(Optional<UUID> parentResourceId) {
			this.parentResourceId = parentResourceId;
		}

		public Integer getCapacityUnits() {
			return this.capacityUnits;
		}

		public void setCapacityUnits(Integer capacityUnits) {
			this.capacityUnits = capacityUnits;
		}

		public Optional<Integer> getMaxPeople() {
			return this.maxPeople;
		}

		public void setMaxPeople(Optional<Integer> maxPeople) {
			this.maxPeople = maxPeople;
		}

		public Optional<String> getDescription() {
			return this.description;
		}

		public void setDescription(Optional<String> description) {
			this.description = description;
		}

		public Boolean getIsActive() {
			return this.isActive;
		}

		public void setIsActive(Boolean isActive) {
			this.isActive = isActive;
		}

		public List<ServiceTypeAssignment> getServiceTypes() {
			return this.serviceTypes;
		}

		public void setServiceTypes(List<ServiceTypeAssignment> serviceTypes) {
			this.serviceTypes = serviceTypes;
		}

	}

	@Schema(name = "Resource", description = "A room with its capacity.")
	record ResourceResponse(
			UUID id,
			@Schema(example = "Sala pole dance") String name,
			ResourceKind kind,
			UUID parentResourceId,
			@Schema(example = "3") int capacityUnits,
			@Schema(example = "12") Integer maxPeople,
			String description,
			boolean isActive) {

		static ResourceResponse from(Resource resource) {
			Short maxPeople = resource.getMaxPeople();
			return new ResourceResponse(resource.getId(), resource.getName(), resource.getKind(),
					resource.getParentResourceId(), resource.getCapacityUnits(),
					(maxPeople != null) ? Integer.valueOf(maxPeople) : null, resource.getDescription(),
					resource.isActive());
		}

	}

	@Schema(name = "ResourceServiceTypeAssignment", description = "A service type allowed to use the room.")
	record ServiceTypeAssignment(
			@NotNull UUID serviceTypeId,
			@Schema(description = "Whether this is the service's default room; a service has at most one.")
			Boolean isDefault) {
	}

	@Schema(name = "ResourceServiceType", description = "A service type allowed to use the room.")
	record ServiceTypeRuleResponse(
			UUID serviceTypeId,
			@Schema(example = "Personal training") String serviceTypeName,
			boolean isDefault) {
	}

}
