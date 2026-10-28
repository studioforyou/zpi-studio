package studioforyou.pl.backend.offering.internal.services;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.offering.internal.services.ServiceTypeRequests.CreateServiceTypeRequest;
import studioforyou.pl.backend.offering.internal.services.ServiceTypeRequests.UpdateServiceTypeRequest;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

@Service
@Transactional
class ServiceTypeManagement {

	private final ServiceTypeRepository serviceTypes;

	private final BookingPolicies bookingPolicies;

	ServiceTypeManagement(ServiceTypeRepository serviceTypes, BookingPolicies bookingPolicies) {
		this.serviceTypes = serviceTypes;
		this.bookingPolicies = bookingPolicies;
	}

	@Transactional(readOnly = true)
	List<ServiceTypeResponse> list(Boolean active) {
		var items = active == null ? this.serviceTypes.findAllByOrderByName()
				: this.serviceTypes.findByActiveOrderByName(active);
		var policies = this.bookingPolicies.findForServices(items.stream().map(ServiceTypeManagement::context).toList());
		return items.stream().map(item -> ServiceTypeResponse.from(item, policies.get(item.getId()))).toList();
	}

	@Transactional(readOnly = true)
	ServiceTypeResponse get(UUID id) {
		return response(this.serviceTypes.findById(id).orElseThrow(ServiceTypeManagement::notFound));
	}

	@Transactional(readOnly = true)
	BookingPolicies.Values bookingDefaults() {
		return this.bookingPolicies.studioDefaults();
	}

	ServiceTypeResponse create(CreateServiceTypeRequest request, UUID actor) {
		ServiceType service = new ServiceType();
		service.setName(request.name().strip());
		service.setDescription(normalize(request.description()));
		service.setCategory(request.category());
		service.setLevel(normalize(request.level()));
		service.setMinAge(toShort(request.minAge()));
		service.setMaxAge(toShort(request.maxAge()));
		service.setDefaultDurationMinutes(toShort(request.defaultDurationMinutes()));
		service.setMinParticipants(toShort(request.minParticipants()));
		service.setMaxParticipants(toShort(request.maxParticipants()));
		service.setCalendarColor(request.calendarColor());
		validateRanges(service);
		this.serviceTypes.saveAndFlush(service);
		this.bookingPolicies.replaceForService(context(service), request.bookingPolicy(), actor);
		return response(service);
	}

	ServiceTypeResponse update(UUID id, UpdateServiceTypeRequest request, UUID actor) {
		ServiceType service = this.serviceTypes.findByIdForUpdate(id)
			.orElseThrow(ServiceTypeManagement::notFound);
		if (request.getName() != null) {
			service.setName(request.getName().strip());
		}
		if (request.getDescription() != null) {
			service.setDescription(normalize(request.getDescription().orElse(null)));
		}
		if (request.getCategory() != null) {
			service.setCategory(request.getCategory());
		}
		if (request.getLevel() != null) {
			service.setLevel(normalize(request.getLevel().orElse(null)));
		}
		if (request.getMinAge() != null) {
			service.setMinAge(toShort(request.getMinAge().orElse(null)));
		}
		if (request.getMaxAge() != null) {
			service.setMaxAge(toShort(request.getMaxAge().orElse(null)));
		}
		if (request.getDefaultDurationMinutes() != null) {
			service.setDefaultDurationMinutes(toShort(request.getDefaultDurationMinutes()));
		}
		if (request.getMinParticipants() != null) {
			service.setMinParticipants(toShort(request.getMinParticipants().orElse(null)));
		}
		if (request.getMaxParticipants() != null) {
			service.setMaxParticipants(toShort(request.getMaxParticipants().orElse(null)));
		}
		if (request.getCalendarColor() != null) {
			service.setCalendarColor(request.getCalendarColor().orElse(null));
		}
		if (request.getIsActive() != null) {
			service.setActive(request.getIsActive());
		}
		validateRanges(service);
		this.serviceTypes.saveAndFlush(service);
		var current = this.bookingPolicies.findForServices(List.of(context(service))).get(id);
		var overrides = request.getBookingPolicy() == null ? current.overrides()
				: request.getBookingPolicy().orElse(null);
		this.bookingPolicies.replaceForService(context(service), overrides, actor);
		return response(service);
	}

	private ServiceTypeResponse response(ServiceType service) {
		return ServiceTypeResponse.from(service,
				this.bookingPolicies.findForServices(List.of(context(service))).get(service.getId()));
	}

	static BookingPolicies.ServiceContext context(ServiceType service) {
		return new BookingPolicies.ServiceContext(service.getId(), !"PERSONAL_TRAINING".equals(service.getCategory()),
				service.getMinParticipants() == null ? null : service.getMinParticipants().intValue(),
				service.getMaxParticipants() == null ? null : service.getMaxParticipants().intValue());
	}

	private static void validateRanges(ServiceType service) {
		if (service.getMinAge() != null && service.getMaxAge() != null
				&& service.getMinAge() > service.getMaxAge()) {
			throw ApiException.invalidField("maxAge", FieldErrorCode.OUT_OF_RANGE,
					"Maximum age must be at least the minimum age.");
		}
		if (service.getMinParticipants() != null && service.getMaxParticipants() != null
				&& service.getMinParticipants() > service.getMaxParticipants()) {
			throw ApiException.invalidField("maxParticipants", FieldErrorCode.OUT_OF_RANGE,
					"Maximum participants must be at least the minimum participants.");
		}
	}

	private static Short toShort(Integer value) {
		return value == null ? null : value.shortValue();
	}

	private static String normalize(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Service type not found.");
	}

}
