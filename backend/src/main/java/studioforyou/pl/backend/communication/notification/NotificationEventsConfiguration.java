package studioforyou.pl.backend.communication.notification;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Lets notification listeners of business events run in the background ({@code @Async}), right
 * after the business transaction commits.
 * <p>
 * A listener for a new notification about a business event looks like this:
 *
 * <pre>{@code
 * @Async
 * @TransactionalEventListener
 * void on(OccurrenceCancelled event) {
 *     notifications.notifyNow(List.of(new NotificationRequest(NotificationType.CLASS_CANCELLED, ...)));
 * }
 * }</pre>
 *
 * The business module publishes the event inside its transaction. Spring Modulith stores it in
 * {@code event_publication} in the same transaction and marks it completed when the listener
 * returns. The listener runs in the background, so a failing listener never turns the user's
 * request into an error after the change was saved. If it throws or the instance stops, the
 * publication stays incomplete and the notification job resubmits it a few minutes later; the
 * request's {@code dedupKey} keeps a resubmitted event from notifying anyone twice.
 */
@Configuration
@EnableAsync
class NotificationEventsConfiguration {

}
