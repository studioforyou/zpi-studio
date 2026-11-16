package studioforyou.pl.backend.customer;

import java.util.Optional;
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

	/**
	 * The staff member's active personal training assignment to the participant, if there is one.
	 * The caller still checks the participant's status and its own permissions.
	 */
	public Optional<ActiveAssignment> findActive(UUID customerId, UUID staffId) {
		return jdbc.sql("""
				SELECT can_view_health AS health_shared
				FROM staff_assignment
				WHERE customer_id = :customerId AND staff_id = :staffId AND scope = 'PERSONAL_TRAINING'
				  AND ended_at IS NULL
				""")
			.param("customerId", customerId)
			.param("staffId", staffId)
			.query(ActiveAssignment.class)
			.optional();
	}

	/**
	 * An active assignment of a staff member to a participant.
	 *
	 * @param healthShared whether the participant agreed to share their health data with this staff
	 * member; the consent belongs to the assignment, so it ends with the cooperation
	 */
	public record ActiveAssignment(boolean healthShared) {

	}

}
