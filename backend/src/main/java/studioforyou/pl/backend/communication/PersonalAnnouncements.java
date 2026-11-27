package studioforyou.pl.backend.communication;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Announcements the studio sent to one person, for other modules' reports.
 */
@Repository
public class PersonalAnnouncements {

	private final JdbcClient jdbc;

	public PersonalAnnouncements(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * When the last announcement addressed to each of the given accounts alone was sent; accounts
	 * that never got one are left out. Announcements to a group, a class or everyone do not count.
	 */
	public Map<UUID, Instant> lastSentTo(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return Map.of();
		}
		record Row(UUID accountId, OffsetDateTime sentAt) {
		}
		return jdbc.sql("""
				SELECT target_account_id AS account_id, max(sent_at) AS sent_at
				FROM announcement
				WHERE audience = 'PERSON' AND target_account_id IN (:ids) AND sent_at IS NOT NULL
				GROUP BY target_account_id
				""")
			.param("ids", List.copyOf(accountIds))
			.query(Row.class)
			.list()
			.stream()
			.collect(Collectors.toMap(Row::accountId, (row) -> row.sentAt().toInstant()));
	}

}
