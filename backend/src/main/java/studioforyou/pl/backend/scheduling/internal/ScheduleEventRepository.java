package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ScheduleEventRepository extends Repository<ScheduleEvent, UUID> {

	/** Loads the event and locks it until the transaction ends. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from ScheduleEvent e where e.id = :id")
	Optional<ScheduleEvent> findByIdForUpdate(@Param("id") UUID id);

	/** Scheduled occurrences of the series that start at or after {@code from}, locked, earliest first. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select e from ScheduleEvent e
			where e.seriesId = :seriesId and e.startsAt >= :from and e.status = :status
			order by e.startsAt
			""")
	List<ScheduleEvent> findSeriesOccurrencesForUpdate(@Param("seriesId") UUID seriesId,
			@Param("from") Instant from, @Param("status") EventStatus status);

	ScheduleEvent save(ScheduleEvent event);

	void flush();

}
