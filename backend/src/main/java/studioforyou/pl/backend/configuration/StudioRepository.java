package studioforyou.pl.backend.configuration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface StudioRepository extends Repository<Studio, UUID> {

	Optional<Studio> findFirstBy();

}
