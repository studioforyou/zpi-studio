package studioforyou.pl.backend.customer;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Current personal-training relationships, without contact or coaching information. */
@Repository
public class TraineeAssignments {

	private final JdbcClient jdbc;

	public TraineeAssignments(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Includes inactive participants so a caller can show their relationship without private details. */
	public List<Trainee> findCurrent(UUID staffId, Instant checkedAt) {
		return jdbc.sql("""
				SELECT c.id, c.first_name || ' ' || c.last_name AS display_name,
				       c.birth_date, c.kind, c.status = 'ACTIVE' AS active
				FROM staff_assignment a
				JOIN customer_profile c ON c.id = a.customer_id
				WHERE a.staff_id = :staffId AND a.scope = 'PERSONAL_TRAINING'
				  AND a.ended_at IS NULL AND a.started_at <= :checkedAt
				ORDER BY c.last_name, c.first_name, c.id
				""")
			.param("staffId", staffId)
			.param("checkedAt", java.time.OffsetDateTime.ofInstant(checkedAt, java.time.ZoneOffset.UTC))
			.query(Trainee.class)
			.list();
	}

	public record Trainee(UUID id, String displayName, LocalDate birthDate, CustomerKind kind, boolean active) {
	}

}
