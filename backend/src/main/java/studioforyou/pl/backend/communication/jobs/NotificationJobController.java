package studioforyou.pl.backend.communication.jobs;

import io.swagger.v3.oas.annotations.Hidden;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point of Cloud Scheduler. {@code /internal/**} is not part of the public API: it only
 * accepts a Google-signed OIDC token of the scheduler's service account (see the identity
 * module's {@code InternalJobsSecurityConfig}).
 */
@Hidden
@RestController
class NotificationJobController {

	private final NotificationJobs jobs;

	NotificationJobController(NotificationJobs jobs) {
		this.jobs = jobs;
	}

	@PostMapping("/internal/jobs/notifications/run")
	NotificationJobs.RunResult run() {
		return jobs.run();
	}

}
