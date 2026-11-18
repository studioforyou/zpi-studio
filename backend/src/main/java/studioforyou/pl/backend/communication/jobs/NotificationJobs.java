package studioforyou.pl.backend.communication.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

import studioforyou.pl.backend.booking.ClassAutoCancellation;
import studioforyou.pl.backend.communication.notification.NotificationDispatcher;
import studioforyou.pl.backend.communication.pass.PassExpiryReminders;
import studioforyou.pl.backend.communication.reminder.ClassReminders;

/**
 * Notification work that depends on time rather than on a request. Cloud Scheduler calls it every
 * 5 minutes through {@link NotificationJobController} (locally {@link LocalNotificationScheduler}
 * can). Notifications about business events are sent right after the event, not here; this job
 * only catches up on the ones whose sending failed or was interrupted.
 * <p>
 * Today the timed notifications are class reminders and reminders about expiring passes. The job
 * also runs the automatic cancellation of classes too few participants booked, first, so a
 * cancelled class gets no reminder and its people are told about the cancellation right away.
 */
@Service
public class NotificationJobs {

	private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

	/** Event publications this old without completion are treated as failed and resubmitted. */
	static final Duration INCOMPLETE_EVENT_AGE = Duration.ofMinutes(2);

	private final ClassAutoCancellation autoCancellation;

	private final ClassReminders classReminders;

	private final PassExpiryReminders passExpiryReminders;

	private final NotificationDispatcher dispatcher;

	private final IncompleteEventPublications incompleteEvents;

	NotificationJobs(ClassAutoCancellation autoCancellation, ClassReminders classReminders,
			PassExpiryReminders passExpiryReminders, NotificationDispatcher dispatcher,
			IncompleteEventPublications incompleteEvents) {
		this.autoCancellation = autoCancellation;
		this.classReminders = classReminders;
		this.passExpiryReminders = passExpiryReminders;
		this.dispatcher = dispatcher;
		this.incompleteEvents = incompleteEvents;
	}

	/** Runs every part independently, so one failing part does not stop the others. */
	public RunResult run() {
		Instant now = Instant.now();
		int cancelled = safely("automatic class cancellation", () -> autoCancellation.cancelDue(now));
		int reminders = safely("class reminders", () -> classReminders.sendDue(now));
		reminders += safely("pass expiry reminders", () -> passExpiryReminders.sendDue(now));
		int retried = safely("delivery retries", () -> dispatcher.retryUndelivered(now));
		safely("incomplete events", () -> {
			incompleteEvents.resubmitIncompletePublicationsOlderThan(INCOMPLETE_EVENT_AGE);
			return 0;
		});
		return new RunResult(now, cancelled, reminders, retried);
	}

	private static int safely(String part, IntSupplier work) {
		try {
			return work.getAsInt();
		}
		catch (RuntimeException ex) {
			log.error("Notification job part failed: {}", part, ex);
			return 0;
		}
	}

	/**
	 * @param classesCancelled classes cancelled automatically for too few participants
	 * @param remindersCreated new reminders about classes and expiring passes
	 * @param deliveriesRetried notifications sent again after a failed or interrupted delivery
	 */
	public record RunResult(Instant ranAt, int classesCancelled, int remindersCreated, int deliveriesRetried) {
	}

}
