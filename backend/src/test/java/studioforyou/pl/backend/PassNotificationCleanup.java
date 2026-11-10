package studioforyou.pl.backend;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Cleanup for tests that commit bookings: a booking that takes the last entry of a pass notifies
 * the participant in the background after the commit ({@code PASS_LAST_ENTRY_USED}). The
 * notification references the account, so a test must wait for it and delete it before it
 * deletes the account.
 */
public final class PassNotificationCleanup {

	private static final Duration WAIT = Duration.ofSeconds(5);

	private PassNotificationCleanup() {
	}

	/** Waits for pending pass notifications and deletes the notifications of the accounts. */
	public static void deleteNotifications(JdbcClient jdbc, Collection<UUID> accounts) {
		awaitListeners(jdbc);
		jdbc.sql("DELETE FROM notification_delivery WHERE notification_id IN "
				+ "(SELECT id FROM notification WHERE recipient_id IN (:accounts))").param("accounts", accounts).update();
		jdbc.sql("DELETE FROM notification WHERE recipient_id IN (:accounts)").param("accounts", accounts).update();
	}

	/**
	 * Waits for pending pass notifications and deletes the notifications of the accounts the query
	 * selects, e.g. {@code SELECT id FROM account WHERE email LIKE '%@example.com'}.
	 */
	public static void deleteNotifications(JdbcClient jdbc, String accountsQuery) {
		awaitListeners(jdbc);
		jdbc.sql("DELETE FROM notification_delivery WHERE notification_id IN "
				+ "(SELECT id FROM notification WHERE recipient_id IN (" + accountsQuery + "))").update();
		jdbc.sql("DELETE FROM notification WHERE recipient_id IN (" + accountsQuery + ")").update();
	}

	private static void awaitListeners(JdbcClient jdbc) {
		Instant deadline = Instant.now().plus(WAIT);
		while (Instant.now().isBefore(deadline) && jdbc.sql("""
				SELECT count(*) FROM event_publication
				WHERE completion_date IS NULL AND event_type LIKE '%PassLastEntryUsed'
				""").query(Long.class).single() > 0) {
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

}
