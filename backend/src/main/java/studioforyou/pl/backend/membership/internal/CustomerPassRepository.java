package studioforyou.pl.backend.membership.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import studioforyou.pl.backend.membership.CustomerPassStatus;

public interface CustomerPassRepository extends JpaRepository<CustomerPass, UUID> {

	List<CustomerPass> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

	List<CustomerPass> findByCustomerIdInOrderByCreatedAtDesc(Collection<UUID> customerIds);

	List<CustomerPass> findByCustomerIdAndStatusNotOrderByCreatedAtDesc(UUID customerId, CustomerPassStatus status);

	Optional<CustomerPass> findByIdAndCustomerId(UUID id, UUID customerId);

	/** Loads the pass and locks it until the transaction ends, as entry accounting does. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM CustomerPass p WHERE p.id = :id AND p.customerId = :customerId")
	Optional<CustomerPass> findForUpdate(UUID id, UUID customerId);

	/** Loads the pass bought with the order line and locks it until the transaction ends. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM CustomerPass p WHERE p.orderItemId = :orderItemId")
	Optional<CustomerPass> findByOrderItemIdForUpdate(UUID orderItemId);

}
