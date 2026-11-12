package studioforyou.pl.backend;

import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cleanup for tests that commit changes which notify someone in the background right after the
 * commit, e.g. a sent message ({@code NEW_MESSAGE}). The notification references the account, so
 * a test must wait for it and delete it before it deletes the account.
 */
public final class NotificationCleanup {

	private static final Duration WAIT = Duration.ofSeconds(5);

	private NotificationCleanup() {
	}

	/**
	 * Waits for the pending notification listeners and deletes the notifications of the accounts the
	 * query selects, e.g. {@code SELECT id FROM account WHERE email LIKE '%@example.com'}.
	 */
	public static void deleteNotifications(JdbcClient jdbc, String accountsQuery) {
		Instant deadline = Instant.now().plus(WAIT);
		while (Instant.now().isBefore(deadline)
				&& jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
					.query(Long.class).single() > 0) {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		jdbc.sql("DELETE FROM notification_delivery WHERE notification_id IN "
				+ "(SELECT id FROM notification WHERE recipient_id IN (" + accountsQuery + "))").update();
		jdbc.sql("DELETE FROM notification WHERE recipient_id IN (" + accountsQuery + ")").update();
	}

}
