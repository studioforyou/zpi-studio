package studioforyou.pl.backend.communication.mail;

/**
 * One e-mail to a single recipient, with an HTML body and a plain-text alternative.
 *
 * @param toEmail the recipient's address
 * @param toName the recipient's display name, or {@code null}
 * @param subject the subject line
 * @param htmlBody the HTML body
 * @param textBody the plain-text body for clients that do not show HTML
 */
public record EmailMessage(String toEmail, String toName, String subject, String htmlBody, String textBody) {
}
