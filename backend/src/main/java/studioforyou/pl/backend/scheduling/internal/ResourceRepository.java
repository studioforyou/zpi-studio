package studioforyou.pl.backend.scheduling.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ResourceRepository extends Repository<Resource, UUID> {

	Optional<Resource> findById(UUID id);

	/**
	 * Loads the room and locks it until the transaction ends, so concurrent edits of the same room
	 * (including its set of service types) run one after another.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Resource r where r.id = :id")
	Optional<Resource> findByIdForUpdate(@Param("id") UUID id);

	List<Resource> findAllByOrderByNameAsc();

	List<Resource> findAllByIdIn(Collection<UUID> ids);

	List<Resource> findAllByActiveOrderByNameAsc(boolean active);

	boolean existsByNameIgnoreCase(String name);

	boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

	Resource saveAndFlush(Resource resource);

}
