package studioforyou.pl.backend.offering.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface PassProductVariantRepository extends Repository<PassProductVariant, UUID> {

	@Query("SELECT v FROM PassProductVariant v JOIN FETCH v.product WHERE v.id = :id")
	Optional<PassProductVariant> findWithProductById(UUID id);

	@Query(value = """
			SELECT EXISTS (
			    SELECT 1 FROM pass_product_variant variant
			    JOIN pass_product_service service ON service.product_id = variant.product_id
			    WHERE variant.id = :variantId AND service.service_type_id = :serviceTypeId
			)
			""", nativeQuery = true)
	boolean coversService(UUID variantId, UUID serviceTypeId);

}
