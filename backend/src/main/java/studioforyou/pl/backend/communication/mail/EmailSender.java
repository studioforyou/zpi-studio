package studioforyou.pl.backend.communication.mail;

/**
 * Sends transactional e-mails (invitations, notifications). The implementation is chosen by
 * {@code app.mail.provider}: {@link BrevoEmailSender} on deployed environments, {@link LogEmailSender} locally.
 */
public interface EmailSender {

	/**
	 * Sends the message from the configured sender address.
	 *
	 * @throws EmailSendingException when the provider rejects the message or cannot be reached
	 */
	void send(EmailMessage message);

}
