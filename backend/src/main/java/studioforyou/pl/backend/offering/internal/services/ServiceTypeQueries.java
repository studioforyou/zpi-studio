package studioforyou.pl.backend.offering.internal.services;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;

@Service
@Transactional(readOnly = true)
class ServiceTypeQueries implements ServiceTypes {

	private final ServiceTypeRepository serviceTypeRepository;

	private final BookingPolicies bookingPolicies;

	ServiceTypeQueries(ServiceTypeRepository serviceTypeRepository, BookingPolicies bookingPolicies) {
		this.serviceTypeRepository = serviceTypeRepository;
		this.bookingPolicies = bookingPolicies;
	}

	@Override
	public Optional<Definition> findDefinitionById(UUID id) {
		return this.serviceTypeRepository.findById(id).map(service -> new Definition(service.getId(), service.getName(),
				service.getCategory(), service.getLevel(), service.getMinAge(), service.getMaxAge(),
				service.getDefaultDurationMinutes(), service.getMinParticipants(), service.getMaxParticipants(),
				service.isActive(), this.bookingPolicies.findForServices(List.of(ServiceTypeManagement.context(service)))
					.get(id)));
	}

	@Override
	public Optional<ServiceTypeSummary> findById(UUID id) {
		return this.serviceTypeRepository.findById(id).map(ServiceTypeQueries::toSummary);
	}

	@Override
	public List<ServiceTypeSummary> findAllById(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return this.serviceTypeRepository.findAllByIdIn(ids).stream().map(ServiceTypeQueries::toSummary).toList();
	}

	@Override
	public List<ServiceTypeSummary> findActive() {
		return this.serviceTypeRepository.findByActiveOrderByName(true)
			.stream()
			.map(ServiceTypeQueries::toSummary)
			.toList();
	}

	private static ServiceTypeSummary toSummary(ServiceType serviceType) {
		Short maxParticipants = serviceType.getMaxParticipants();
		return new ServiceTypeSummary(serviceType.getId(), serviceType.getName(), serviceType.isActive(),
				serviceType.getCategory(), serviceType.getDefaultDurationMinutes(),
				(maxParticipants != null) ? Integer.valueOf(maxParticipants) : null, serviceType.getCalendarColor());
	}

}
