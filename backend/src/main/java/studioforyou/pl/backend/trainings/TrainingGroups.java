package studioforyou.pl.backend.trainings;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Fixed training groups and their current members, for other modules.
 */
@Repository
public class TrainingGroups {

	private final JdbcClient jdbc;

	public TrainingGroups(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Names of the given groups, whatever their status; unknown ids are left out. */
	public Map<UUID, String> names(Collection<UUID> groupIds) {
		if (groupIds.isEmpty()) {
			return Map.of();
		}
		record Row(UUID id, String name) {
		}
		return jdbc.sql("SELECT id, name FROM training_group WHERE id IN (:ids)")
			.param("ids", groupIds)
			.query(Row.class)
			.list()
			.stream()
			.collect(Collectors.toMap(Row::id, Row::name));
	}

	/** Groups any of the participants currently belongs to. */
	public Set<UUID> findGroupsOf(Collection<UUID> profileIds, LocalDate today) {
		if (profileIds.isEmpty()) {
			return Set.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT group_id
				FROM group_membership
				WHERE customer_id IN (:ids) AND joined_on <= :today AND (left_on IS NULL OR left_on > :today)
				""")
			.param("ids", profileIds)
			.param("today", today)
			.query(UUID.class)
			.set();
	}

	/** Participants who currently belong to the group. */
	public Set<UUID> findMembers(UUID groupId, LocalDate today) {
		return jdbc.sql("""
				SELECT customer_id
				FROM group_membership
				WHERE group_id = :groupId AND joined_on <= :today AND (left_on IS NULL OR left_on > :today)
				""")
			.param("groupId", groupId)
			.param("today", today)
			.query(UUID.class)
			.set();
	}

}
