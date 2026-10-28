package studioforyou.pl.backend.communication.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BrevoEmailSenderTest {

	private static final MailProperties PROPERTIES = new MailProperties(MailProperties.Provider.BREVO,
			"no-reply@studioforyou.local", "STUDIO FOR YOU",
			new MailProperties.Brevo("test-api-key", "https://api.brevo.test"));

	private static final EmailMessage MESSAGE = new EmailMessage("anna@example.com", "Anna", "Zaproszenie",
			"<p>Cześć</p>", "Cześć");

	private MockRestServiceServer server;

	private BrevoEmailSender sender;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.sender = new BrevoEmailSender(builder, PROPERTIES);
	}

	@Test
	void postsTheMessageToBrevoWithTheApiKey() {
		this.server.expect(requestTo("https://api.brevo.test/v3/smtp/email"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("api-key", "test-api-key"))
			.andExpect(content().json("""
					{
					  "sender": {"email": "no-reply@studioforyou.local", "name": "STUDIO FOR YOU"},
					  "to": [{"email": "anna@example.com", "name": "Anna"}],
					  "subject": "Zaproszenie",
					  "htmlContent": "<p>Cześć</p>",
					  "textContent": "Cześć"
					}
					"""))
			.andRespond(withSuccess("{\"messageId\":\"<1@smtp-relay.brevo.com>\"}", MediaType.APPLICATION_JSON));

		this.sender.send(MESSAGE);

		this.server.verify();
	}

	@Test
	void rejectedMessageThrowsEmailSendingException() {
		this.server.expect(requestTo("https://api.brevo.test/v3/smtp/email"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> this.sender.send(MESSAGE)).isInstanceOf(EmailSendingException.class)
			.hasMessageContaining("anna@example.com");
	}

}
