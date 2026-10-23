package studioforyou.pl.backend.membership.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPassRepository extends JpaRepository<CustomerPass, UUID> {

	List<CustomerPass> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

	Optional<CustomerPass> findByIdAndCustomerId(UUID id, UUID customerId);

}
