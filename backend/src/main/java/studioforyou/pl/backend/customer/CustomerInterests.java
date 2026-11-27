package studioforyou.pl.backend.customer;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The service types participants marked as interesting ({@code customer_interest}), so they are
 * told when booking of such a class opens. The participant or their guardian marks them.
 */
@Repository
public class CustomerInterests {

	private final JdbcClient jdbc;

	public CustomerInterests(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The service types the participant marked, oldest first. */
	public List<UUID> findServiceTypes(UUID profileId) {
		return jdbc.sql("""
				SELECT service_type_id FROM customer_interest
				WHERE customer_id = :profile ORDER BY created_at, service_type_id
				""")
			.param("profile", profileId)
			.query(UUID.class)
			.list();
	}

	/**
	 * Marks the service type for the participant; marking it again changes nothing.
	 *
	 * @return whether it was not marked before
	 */
	public boolean add(UUID profileId, UUID serviceTypeId) {
		return jdbc.sql("""
				INSERT INTO customer_interest (customer_id, service_type_id) VALUES (:profile, :service)
				ON CONFLICT DO NOTHING
				""")
			.param("profile", profileId)
			.param("service", serviceTypeId)
			.update() > 0;
	}

	/**
	 * Unmarks the service type for the participant.
	 *
	 * @return whether it was marked
	 */
	public boolean remove(UUID profileId, UUID serviceTypeId) {
		return jdbc.sql("DELETE FROM customer_interest WHERE customer_id = :profile AND service_type_id = :service")
			.param("profile", profileId)
			.param("service", serviceTypeId)
			.update() > 0;
	}

	/** Per service type, the active participants who marked it. */
	public Map<UUID, Set<UUID>> findInterested(Collection<UUID> serviceTypeIds) {
		if (serviceTypeIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Set<UUID>> interested = new HashMap<>();
		jdbc.sql("""
				SELECT i.service_type_id, i.customer_id
				FROM customer_interest i
				JOIN customer_profile p ON p.id = i.customer_id
				WHERE i.service_type_id IN (:services) AND p.status = 'ACTIVE'
				""")
			.param("services", List.copyOf(serviceTypeIds))
			.query((rs, row) -> Map.entry(rs.getObject("service_type_id", UUID.class),
					rs.getObject("customer_id", UUID.class)))
			.list()
			.forEach((entry) -> interested.computeIfAbsent(entry.getKey(), (key) -> new HashSet<>())
				.add(entry.getValue()));
		return interested;
	}

}
