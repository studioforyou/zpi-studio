package studioforyou.pl.backend.membership.internal;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/** Serializes pass accounting in the caller's transaction, including cached eligibility reads. */
@Component
class CustomerPassLock {

	private final CustomerPassRepository passes;

	private final JdbcClient jdbc;

	private final EntityManager entityManager;

	CustomerPassLock(CustomerPassRepository passes, JdbcClient jdbc, EntityManager entityManager) {
		this.passes = passes;
		this.jdbc = jdbc;
		this.entityManager = entityManager;
	}

	CustomerPass acquire(UUID customerId, UUID passId) {
		jdbc.sql("SELECT id FROM customer_pass WHERE id = :passId AND customer_id = :customerId FOR UPDATE")
			.param("passId", passId).param("customerId", customerId).query(UUID.class).optional()
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass not found."));
		CustomerPass pass = passes.findByIdAndCustomerId(passId, customerId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Pass not found."));
		// Lock before refreshing: an earlier read may have cached the old balance and version.
		entityManager.refresh(pass);
		return pass;
	}

}
