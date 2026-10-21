package studioforyou.pl.backend.offering.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface ServiceTypeRepository extends Repository<ServiceType, UUID> {

	Optional<ServiceType> findById(UUID id);

	List<ServiceType> findAllByIdIn(Collection<UUID> ids);

	List<ServiceType> findAllByActiveTrueOrderByNameAsc();

}
