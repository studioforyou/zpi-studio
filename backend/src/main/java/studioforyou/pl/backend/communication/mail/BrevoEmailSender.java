package studioforyou.pl.backend.communication.mail;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends e-mails through the Brevo transactional e-mail API ({@code POST /v3/smtp/email}).
 */
class BrevoEmailSender implements EmailSender {

	private final RestClient restClient;

	private final Contact sender;

	BrevoEmailSender(RestClient.Builder restClientBuilder, MailProperties properties) {
		this.restClient = restClientBuilder.baseUrl(properties.brevo().baseUrl())
			.defaultHeader("api-key", properties.brevo().apiKey())
			.build();
		this.sender = new Contact(properties.fromEmail(), properties.fromName());
	}

	@Override
	public void send(EmailMessage message) {
		var request = new SendEmailRequest(this.sender, List.of(new Contact(message.toEmail(), message.toName())),
				message.subject(), message.htmlBody(), message.textBody());
		try {
			this.restClient.post()
				.uri("/v3/smtp/email")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientException ex) {
			throw new EmailSendingException("Brevo did not accept the e-mail to " + message.toEmail(), ex);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Contact(String email, String name) {
	}

	record SendEmailRequest(Contact sender, List<Contact> to, String subject, String htmlContent,
			String textContent) {
	}

}
