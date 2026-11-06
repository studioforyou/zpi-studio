package studioforyou.pl.backend.membership.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface PassLedgerRepository extends Repository<PassLedgerEntry, UUID> {

	PassLedgerEntry save(PassLedgerEntry entry);

	Optional<PassLedgerEntry> findByPassIdAndReservationIdAndKind(UUID passId, UUID reservationId, PassLedgerKind kind);

	boolean existsByPassIdAndReservationIdAndKind(UUID passId, UUID reservationId, PassLedgerKind kind);

	/** Entries still allocated to reservations, which may later be returned. */
	@Query(value = """
			SELECT COALESCE(SUM(-CAST(allocated.entries_delta AS INTEGER)), 0)
			FROM pass_ledger allocated
			WHERE allocated.pass_id = :passId AND allocated.kind = 'ENTRY_RESERVED'
			  AND allocated.entries_delta < 0
			  AND NOT EXISTS (
			      SELECT 1 FROM pass_ledger settled
			      WHERE settled.pass_id = allocated.pass_id
			        AND settled.reservation_id = allocated.reservation_id
			        AND settled.kind IN ('ENTRY_RETURNED', 'ENTRY_CONSUMED')
			  )
			""", nativeQuery = true)
	long pendingReturns(@Param("passId") UUID passId);

}
