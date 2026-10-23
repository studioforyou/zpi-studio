package studioforyou.pl.backend.membership.internal;

import java.util.UUID;

import org.springframework.data.repository.Repository;

interface PassLedgerRepository extends Repository<PassLedgerEntry, UUID> {

	PassLedgerEntry save(PassLedgerEntry entry);

}
