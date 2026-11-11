package studioforyou.pl.backend.customer;

import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Whether a trainer currently works with a participant, for other modules.
 */
@Repository
public class StaffAssignments {

	private final JdbcClient jdbc;

	public StaffAssignments(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Whether the staff member has an active personal training assignment to the participant. */
	public boolean isAssigned(UUID customerId, UUID staffId) {
		return jdbc.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM staff_assignment
				  WHERE customer_id = :customerId AND staff_id = :staffId AND scope = 'PERSONAL_TRAINING'
				    AND ended_at IS NULL
				)
				""")
			.param("customerId", customerId)
			.param("staffId", staffId)
			.query(Boolean.class)
			.single();
	}

	/** Participants the staff member has an active personal training assignment to. */
	public Set<UUID> findAssignedCustomers(UUID staffId) {
		return jdbc.sql("""
				SELECT customer_id FROM staff_assignment
				WHERE staff_id = :staffId AND scope = 'PERSONAL_TRAINING' AND ended_at IS NULL
				""")
			.param("staffId", staffId)
			.query(UUID.class)
			.set();
	}

}
