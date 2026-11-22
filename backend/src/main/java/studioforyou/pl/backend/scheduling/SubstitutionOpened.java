package studioforyou.pl.backend.scheduling;

import java.util.UUID;

/** A request opened or became available again; recipients are checked after commit. */
public record SubstitutionOpened(UUID requestId, int version) {
}
