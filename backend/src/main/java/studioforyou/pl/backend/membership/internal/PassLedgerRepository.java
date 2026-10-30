package studioforyou.pl.backend.membership.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface PassLedgerRepository extends Repository<PassLedgerEntry, UUID> {

	PassLedgerEntry save(PassLedgerEntry entry);

	Optional<PassLedgerEntry> findByPassIdAndReservationIdAndKind(UUID passId, UUID reservationId, PassLedgerKind kind);

	boolean existsByPassIdAndReservationIdAndKind(UUID passId, UUID reservationId, PassLedgerKind kind);

}
