package studioforyou.pl.backend.offering.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class ServiceTypeQueries implements ServiceTypes {

	private final ServiceTypeRepository serviceTypeRepository;

	ServiceTypeQueries(ServiceTypeRepository serviceTypeRepository) {
		this.serviceTypeRepository = serviceTypeRepository;
	}

	@Override
	public Optional<ServiceTypeSummary> findById(UUID id) {
		return this.serviceTypeRepository.findById(id).map(ServiceType::toSummary);
	}

	@Override
	public List<ServiceTypeSummary> findAllById(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return this.serviceTypeRepository.findAllByIdIn(ids).stream().map(ServiceType::toSummary).toList();
	}

	@Override
	public List<ServiceTypeSummary> findActive() {
		return this.serviceTypeRepository.findAllByActiveTrueOrderByNameAsc()
			.stream()
			.map(ServiceType::toSummary)
			.toList();
	}

}
