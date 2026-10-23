package studioforyou.pl.backend.scheduling.internal;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.CreateResourceRequest;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.ResourceResponse;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.ServiceTypeAssignment;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.ServiceTypeRuleResponse;
import studioforyou.pl.backend.scheduling.internal.ResourceDtos.UpdateResourceRequest;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defines rooms and which service types may take place in them.
 */
@Service
@Transactional
class ResourceManagement {

	private final ResourceRepository resourceRepository;

	private final ServiceTypeResourceRepository ruleRepository;

	private final ServiceTypes serviceTypes;

	ResourceManagement(ResourceRepository resourceRepository, ServiceTypeResourceRepository ruleRepository,
			ServiceTypes serviceTypes) {
		this.resourceRepository = resourceRepository;
		this.ruleRepository = ruleRepository;
		this.serviceTypes = serviceTypes;
	}

	@Transactional(readOnly = true)
	List<ResourceResponse> list(@Nullable Boolean active) {
		List<Resource> resources = (active != null) ? this.resourceRepository.findAllByActiveOrderByNameAsc(active)
				: this.resourceRepository.findAllByOrderByNameAsc();
		return resources.stream().map(ResourceResponse::from).toList();
	}

	@Transactional(readOnly = true)
	ResourceResponse get(UUID id) {
		return ResourceResponse.from(findResource(id));
	}

	ResourceResponse create(CreateResourceRequest request) {
		String name = request.name().trim();
		if (this.resourceRepository.existsByNameIgnoreCase(name)) {
			throw nameAlreadyUsed();
		}
		if (request.parentResourceId() != null) {
			requireParentExists(request.parentResourceId());
		}
		Resource resource = new Resource(name, request.kind());
		resource.setParentResourceId(request.parentResourceId());
		resource.setMaxPeople(toShort(request.maxPeople()));
		resource.setDescription(normalizeDescription(request.description()));
		ResourceResponse response = ResourceResponse.from(saveResource(resource));
		if (request.serviceTypes() != null) {
			replaceServiceTypes(response.id(), request.serviceTypes());
		}
		return response;
	}

	ResourceResponse update(UUID id, UpdateResourceRequest request) {
		Resource resource = this.resourceRepository.findByIdForUpdate(id).orElseThrow(ResourceManagement::roomNotFound);
		if (request.getName() != null) {
			String name = request.getName().trim();
			if (this.resourceRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
				throw nameAlreadyUsed();
			}
			resource.setName(name);
		}
		if (request.getKind() != null) {
			resource.setKind(request.getKind());
		}
		if (request.getParentResourceId() != null) {
			UUID parentId = request.getParentResourceId().orElse(null);
			if (parentId != null) {
				requireValidParent(id, parentId);
			}
			resource.setParentResourceId(parentId);
		}
		if (request.getMaxPeople() != null) {
			resource.setMaxPeople(toShort(request.getMaxPeople().orElse(null)));
		}
		if (request.getDescription() != null) {
			resource.setDescription(normalizeDescription(request.getDescription().orElse(null)));
		}
		if (request.getIsActive() != null) {
			resource.setActive(request.getIsActive());
		}
		ResourceResponse response = ResourceResponse.from(saveResource(resource));
		if (request.getServiceTypes() != null) {
			replaceServiceTypes(id, request.getServiceTypes());
		}
		return response;
	}

	@Transactional(readOnly = true)
	List<ServiceTypeRuleResponse> listRules(UUID resourceId) {
		findResource(resourceId);
		List<ServiceTypeResource> rules = this.ruleRepository.findAllByKeyResourceId(resourceId);
		Map<UUID, String> names = this.serviceTypes
			.findAllById(rules.stream().map(ServiceTypeResource::getServiceTypeId).toList())
			.stream()
			.collect(Collectors.toMap(ServiceTypeSummary::id, ServiceTypeSummary::name));
		return rules.stream()
			.map((rule) -> new ServiceTypeRuleResponse(rule.getServiceTypeId(), names.get(rule.getServiceTypeId()),
					rule.isDefaultResource()))
			.sorted(Comparator.comparing(ServiceTypeRuleResponse::serviceTypeName,
					Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
			.toList();
	}

	/**
	 * Makes the given service types the only ones allowed in the room. A newly added service type
	 * must be active; one that is already allowed may stay even if it was deactivated since.
	 */
	private void replaceServiceTypes(UUID resourceId, List<ServiceTypeAssignment> assignments) {
		Map<UUID, Boolean> requested = assignments.stream()
			.collect(Collectors.toMap(ServiceTypeAssignment::serviceTypeId,
					(assignment) -> Boolean.TRUE.equals(assignment.isDefault()), (first, second) -> {
						throw new ApiException(ErrorCode.VALIDATION_FAILED,
								"Each service type may appear only once.");
					}));
		Set<UUID> current = this.ruleRepository.findAllByKeyResourceId(resourceId)
			.stream()
			.map(ServiceTypeResource::getServiceTypeId)
			.collect(Collectors.toSet());
		Set<UUID> added = requested.keySet()
			.stream()
			.filter((serviceTypeId) -> !current.contains(serviceTypeId))
			.collect(Collectors.toSet());
		Set<UUID> activeAdded = this.serviceTypes.findAllById(added)
			.stream()
			.filter(ServiceTypeSummary::active)
			.map(ServiceTypeSummary::id)
			.collect(Collectors.toSet());
		if (!activeAdded.containsAll(added)) {
			throw new ApiException(ErrorCode.SERVICE_TYPE_UNAVAILABLE,
					"A service type does not exist or is not active.");
		}

		// Runs before the rules are loaded for changes: it clears the persistence context.
		requested.forEach((serviceTypeId, isDefault) -> {
			if (isDefault) {
				this.ruleRepository.clearOtherDefaults(serviceTypeId, resourceId);
			}
		});
		Map<UUID, ServiceTypeResource> rules = this.ruleRepository.findAllByKeyResourceId(resourceId)
			.stream()
			.collect(Collectors.toMap(ServiceTypeResource::getServiceTypeId, Function.identity()));
		rules.forEach((serviceTypeId, rule) -> {
			if (requested.containsKey(serviceTypeId)) {
				rule.setDefaultResource(requested.get(serviceTypeId));
			}
			else {
				this.ruleRepository.delete(rule);
			}
		});
		added.forEach((serviceTypeId) -> this.ruleRepository
			.save(new ServiceTypeResource(resourceId, serviceTypeId, requested.get(serviceTypeId))));
		this.ruleRepository.flush();
	}

	private Resource findResource(UUID id) {
		return this.resourceRepository.findById(id).orElseThrow(ResourceManagement::roomNotFound);
	}

	private Resource saveResource(Resource resource) {
		try {
			return this.resourceRepository.saveAndFlush(resource);
		}
		catch (DataIntegrityViolationException ex) {
			// Another request took the name between the check and the insert.
			throw nameAlreadyUsed();
		}
	}

	private void requireParentExists(UUID parentId) {
		if (this.resourceRepository.findById(parentId).isEmpty()) {
			throw invalidParent("The parent room does not exist.");
		}
	}

	/**
	 * Checks that the parent exists and that making it the parent creates no cycle.
	 */
	private void requireValidParent(UUID id, UUID parentId) {
		Set<UUID> visited = new HashSet<>();
		UUID current = parentId;
		while (current != null) {
			if (current.equals(id)) {
				throw invalidParent("A room cannot be its own parent or a part of its own part.");
			}
			if (!visited.add(current)) {
				break;
			}
			UUID ancestorId = current;
			current = this.resourceRepository.findById(ancestorId)
				.orElseThrow(() -> invalidParent("The parent room does not exist."))
				.getParentResourceId();
		}
	}

	private static @Nullable Short toShort(@Nullable Integer value) {
		return (value != null) ? value.shortValue() : null;
	}

	private static @Nullable String normalizeDescription(@Nullable String description) {
		return (description == null || description.isBlank()) ? null : description.trim();
	}

	private static ApiException roomNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Room not found.");
	}

	private static ApiException nameAlreadyUsed() {
		return new ApiException(ErrorCode.RESOURCE_NAME_ALREADY_USED, "A room with this name already exists.");
	}

	private static ApiException invalidParent(String detail) {
		return new ApiException(ErrorCode.RESOURCE_PARENT_INVALID, detail);
	}

}
