package studioforyou.pl.backend.scheduling.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface RecurrenceSeriesRepository extends Repository<RecurrenceSeries, UUID> {

	Optional<RecurrenceSeries> findById(UUID id);

	RecurrenceSeries save(RecurrenceSeries series);

}
