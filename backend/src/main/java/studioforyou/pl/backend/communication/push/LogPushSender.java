package studioforyou.pl.backend.communication.push;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes push notifications to the log instead of sending them, so that local development needs
 * no Firebase credentials.
 */
class LogPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(LogPushSender.class);

	@Override
	public PushResult send(PushMessage message) {
		log.info("Push not sent (app.push.provider=LOG). Token: {}..., title: {}, body: {}, data: {}",
				message.token().substring(0, Math.min(12, message.token().length())), message.title(), message.body(),
				message.data());
		return PushResult.sent("log-" + UUID.randomUUID());
	}

}
