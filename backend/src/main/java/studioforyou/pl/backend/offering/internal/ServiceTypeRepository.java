package studioforyou.pl.backend.offering.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface ServiceTypeRepository extends Repository<ServiceType, UUID> {

	List<ServiceType> findAllByOrderByName();

	List<ServiceType> findByActiveOrderByName(boolean active);

	long countByIdInAndActiveTrue(Collection<UUID> ids);

}
