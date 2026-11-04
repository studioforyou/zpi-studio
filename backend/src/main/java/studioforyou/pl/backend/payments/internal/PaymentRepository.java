package studioforyou.pl.backend.payments.internal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentRepository extends JpaRepository<Payment, UUID> {

}
