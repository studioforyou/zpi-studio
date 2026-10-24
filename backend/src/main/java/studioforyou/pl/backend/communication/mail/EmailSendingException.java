package studioforyou.pl.backend.communication.mail;

/**
 * The e-mail provider rejected a message or could not be reached.
 */
public class EmailSendingException extends RuntimeException {

	public EmailSendingException(String message, Throwable cause) {
		super(message, cause);
	}

}
