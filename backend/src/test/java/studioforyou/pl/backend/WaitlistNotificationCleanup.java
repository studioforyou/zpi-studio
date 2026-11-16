package studioforyou.pl.backend;

import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import static org.awaitility.Awaitility.await;

/** Waits for this fixture's asynchronous listeners before removing its dependent rows. */
public final class WaitlistNotificationCleanup {

	private WaitlistNotificationCleanup() {}

	public static void awaitSettled(JdbcClient jdbc, UUID serviceId) {
		if (serviceId == null) return;
		await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
				SELECT count(*) FROM event_publication
				WHERE event_type = 'studioforyou.pl.backend.booking.WaitlistPlaceAllocated'
				 AND completion_date IS NULL AND serialized_event::jsonb->>'eventId' IN
				 (SELECT id::text FROM schedule_event WHERE service_type_id = :service)
				""").param("service", serviceId).query(Long.class).single() == 0);
	}

	public static void removeForService(JdbcClient jdbc, UUID serviceId) {
		if (serviceId == null) return;
		awaitSettled(jdbc, serviceId);
		String notifications = "SELECT id FROM notification WHERE payload->>'eventId' IN "
				+ "(SELECT id::text FROM schedule_event WHERE service_type_id = :service)";
		jdbc.sql("DELETE FROM notification_delivery WHERE notification_id IN (" + notifications + ")")
			.param("service", serviceId).update();
		jdbc.sql("DELETE FROM notification WHERE id IN (" + notifications + ")").param("service", serviceId).update();
		jdbc.sql("""
				DELETE FROM event_publication
				WHERE event_type = 'studioforyou.pl.backend.booking.WaitlistPlaceAllocated'
				 AND serialized_event::jsonb->>'eventId' IN
				 (SELECT id::text FROM schedule_event WHERE service_type_id = :service)
				""").param("service", serviceId).update();
	}

}
