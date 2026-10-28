package studioforyou.pl.backend.offering;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import studioforyou.pl.backend.configuration.BookingPolicies;

/**
 * Read-only queries about the service catalogue for other modules.
 */
public interface ServiceTypes {

	Optional<ServiceTypeSummary> findById(UUID id);

	List<ServiceTypeSummary> findAllById(Collection<UUID> ids);

	List<ServiceTypeSummary> findActive();

	/** Full service terms for booking and scheduling, including effective cancellation settings. */
	Optional<Definition> findDefinitionById(UUID id);

	record Definition(UUID id, String name, String category, String level, Short minAge, Short maxAge,
			Short defaultDurationMinutes, Short minParticipants, Short maxParticipants,
			boolean active, BookingPolicies.Definition bookingPolicy) {
	}

}
