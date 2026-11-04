package studioforyou.pl.backend.payments.internal;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

	/** Loads the order and locks it until the transaction ends, so it is paid only once. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM CustomerOrder o WHERE o.id = :id")
	Optional<CustomerOrder> findForUpdate(UUID id);

}
