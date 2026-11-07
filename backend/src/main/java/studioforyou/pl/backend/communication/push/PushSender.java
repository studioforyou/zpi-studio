package studioforyou.pl.backend.communication.push;

/**
 * Sends push notifications to devices. The implementation is chosen by {@code app.push.provider}:
 * Firebase Cloud Messaging, or only the log for local development and tests.
 */
public interface PushSender {

	/** Sends the message; never throws for a provider error, the result says what happened. */
	PushResult send(PushMessage message);

}
