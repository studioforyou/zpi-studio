package studioforyou.pl.backend.communication.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes e-mails to the log instead of sending them, so that local development needs no provider account.
 * Links in the e-mail (e.g. an invitation) can be copied from the log.
 */
class LogEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

	@Override
	public void send(EmailMessage message) {
		log.info("E-mail not sent (app.mail.provider=LOG). To: {}, subject: {}\n{}", message.toEmail(),
				message.subject(), message.textBody());
	}

}
