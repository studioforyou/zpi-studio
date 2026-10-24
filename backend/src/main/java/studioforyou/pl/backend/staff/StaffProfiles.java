package studioforyou.pl.backend.staff;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read access to staff profiles for other modules.
 */
@Repository
public class StaffProfiles {

	private final JdbcClient jdbc;

	public StaffProfiles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The account's staff profile, if it has one. */
	public Optional<StaffSummary> findByAccount(UUID accountId) {
		return jdbc.sql("""
				SELECT id, first_name || ' ' || last_name AS display_name
				FROM staff_profile
				WHERE account_id = :accountId
				""")
			.param("accountId", accountId)
			.query(StaffSummary.class)
			.optional();
	}

	/** The given staff members, whatever their status; unknown ids are left out. */
	public List<StaffMember> findAllById(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id, first_name, last_name, calendar_color, status = 'ACTIVE' AS active
				FROM staff_profile
				WHERE id IN (:ids)
				""")
			.param("ids", ids)
			.query(StaffMember.class)
			.list();
	}

	/**
	 * Staff members sorted by last and first name.
	 *
	 * @param active {@code true} for active staff only, {@code false} for inactive only, {@code null} for all
	 */
	public List<StaffMember> findAll(@Nullable Boolean active) {
		String where = (active == null) ? "" : "WHERE (status = 'ACTIVE') = :active";
		var query = jdbc.sql("""
				SELECT id, first_name, last_name, calendar_color, status = 'ACTIVE' AS active
				FROM staff_profile
				%s
				ORDER BY last_name, first_name, id
				""".formatted(where));
		if (active != null) {
			query = query.param("active", active);
		}
		return query.query(StaffMember.class).list();
	}

	/**
	 * The service types each of the given staff members is qualified to run. Staff members
	 * without any qualification are missing from the map.
	 */
	public Map<UUID, Set<UUID>> findQualifications(Collection<UUID> staffIds) {
		Map<UUID, Set<UUID>> result = new HashMap<>();
		if (staffIds.isEmpty()) {
			return result;
		}
		jdbc.sql("""
				SELECT staff_id, service_type_id
				FROM staff_qualification
				WHERE staff_id IN (:staffIds)
				""")
			.param("staffIds", staffIds)
			.query((rs) -> {
				result.computeIfAbsent(rs.getObject("staff_id", UUID.class), (id) -> new HashSet<>())
					.add(rs.getObject("service_type_id", UUID.class));
			});
		return result;
	}

	public record StaffSummary(UUID id, String displayName) {

	}

	/**
	 * A staff member as other modules see them.
	 *
	 * @param calendarColor the colour of the staff member in calendars, {@code #RRGGBB}, or {@code null}
	 * @param active whether the profile has status {@code ACTIVE}
	 */
	public record StaffMember(UUID id, String firstName, String lastName, @Nullable String calendarColor,
			boolean active) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

}
