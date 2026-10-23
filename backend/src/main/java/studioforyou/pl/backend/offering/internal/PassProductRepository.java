package studioforyou.pl.backend.offering.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PassProductRepository extends JpaRepository<PassProduct, UUID> {

	List<PassProduct> findAllByOrderByName();

	List<PassProduct> findByActiveOrderByName(boolean active);

}
