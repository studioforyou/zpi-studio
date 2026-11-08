package studioforyou.pl.backend.communication.jobs;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the notification job in the application itself, for local development where there is no
 * Cloud Scheduler ({@code app.jobs.local-scheduler.enabled=true}). Not for Cloud Run: there the CPU
 * is throttled between requests, so Cloud Scheduler calls the job endpoint instead.
 */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty("app.jobs.local-scheduler.enabled")
class LocalNotificationScheduler {

	private final NotificationJobs jobs;

	LocalNotificationScheduler(NotificationJobs jobs) {
		this.jobs = jobs;
	}

	@Scheduled(fixedDelayString = "${app.jobs.local-scheduler.interval:PT5M}", initialDelayString = "PT1M")
	void run() {
		jobs.run();
	}

}
