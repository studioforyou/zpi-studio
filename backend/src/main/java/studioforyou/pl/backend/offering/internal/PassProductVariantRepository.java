package studioforyou.pl.backend.offering.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PassProductVariantRepository extends Repository<PassProductVariant, UUID> {

	@Query("SELECT v FROM PassProductVariant v JOIN FETCH v.product WHERE v.id = :id")
	Optional<PassProductVariant> findWithProductById(UUID id);

}
