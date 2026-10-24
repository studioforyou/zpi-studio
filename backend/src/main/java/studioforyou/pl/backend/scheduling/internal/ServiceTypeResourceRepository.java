package studioforyou.pl.backend.scheduling.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ServiceTypeResourceRepository extends Repository<ServiceTypeResource, ServiceTypeResource.Key> {

	List<ServiceTypeResource> findAllByKeyResourceId(UUID resourceId);

	List<ServiceTypeResource> findAllByKeyServiceTypeId(UUID serviceTypeId);

	/**
	 * A service type has at most one default room, so making a room the default clears the others.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update ServiceTypeResource r set r.defaultResource = false
			where r.key.serviceTypeId = :serviceTypeId and r.key.resourceId <> :resourceId and r.defaultResource = true
			""")
	void clearOtherDefaults(@Param("serviceTypeId") UUID serviceTypeId, @Param("resourceId") UUID resourceId);

	ServiceTypeResource save(ServiceTypeResource rule);

	void delete(ServiceTypeResource rule);

	void flush();

}
