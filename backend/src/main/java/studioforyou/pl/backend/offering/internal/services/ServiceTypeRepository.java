package studioforyou.pl.backend.offering.internal.services;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.Repository;

public interface ServiceTypeRepository extends Repository<ServiceType, UUID> {

	List<ServiceType> findAllByOrderByName();

	List<ServiceType> findByActiveOrderByName(boolean active);

	long countByIdInAndActiveTrue(Collection<UUID> ids);

	Optional<ServiceType> findById(UUID id);

	ServiceType saveAndFlush(ServiceType serviceType);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from ServiceType s where s.id = :id")
	Optional<ServiceType> findByIdForUpdate(UUID id);

	List<ServiceType> findAllByIdIn(Collection<UUID> ids);

}
