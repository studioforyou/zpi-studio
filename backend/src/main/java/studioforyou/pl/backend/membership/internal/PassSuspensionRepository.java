package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

interface PassSuspensionRepository extends Repository<PassSuspension, UUID> {

	PassSuspension save(PassSuspension suspension);

	Optional<PassSuspension> findByIdAndPassId(UUID id, UUID passId);

	/** Suspensions that were not cancelled, oldest first. */
	List<PassSuspension> findByPassIdInAndCancelledAtIsNullOrderByStartsOn(Collection<UUID> passIds);

	@Query("""
			SELECT count(s) > 0 FROM PassSuspension s
			WHERE s.passId = :passId AND s.cancelledAt IS NULL AND s.startsOn <= :endsOn AND s.endsOn >= :startsOn
			""")
	boolean overlaps(UUID passId, LocalDate startsOn, LocalDate endsOn);

}
