package studioforyou.pl.backend.offering;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only queries about the service catalogue for other modules.
 */
public interface ServiceTypes {

	Optional<ServiceTypeSummary> findById(UUID id);

	List<ServiceTypeSummary> findAllById(Collection<UUID> ids);

	List<ServiceTypeSummary> findActive();

}
