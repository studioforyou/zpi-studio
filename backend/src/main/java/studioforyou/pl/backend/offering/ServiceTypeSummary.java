package studioforyou.pl.backend.offering;

import java.util.UUID;

/**
 * Basic information about a service type that other modules may rely on.
 */
public record ServiceTypeSummary(UUID id, String name, boolean active) {
}
