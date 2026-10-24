package studioforyou.pl.backend.communication.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * E-mail settings ({@code app.mail.*}).
 *
 * @param provider which {@link EmailSender} to use
 * @param fromEmail the sender address; with Brevo it must be a verified sender
 * @param fromName the sender's display name
 * @param brevo Brevo API settings, used only when {@code provider} is {@code BREVO}
 */
@Validated
@ConfigurationProperties("app.mail")
public record MailProperties(@NotNull Provider provider, @NotBlank @Email String fromEmail, @NotBlank String fromName,
		Brevo brevo) {

	public enum Provider {

		/** Sends through the Brevo transactional e-mail API. */
		BREVO,

		/** Only writes the e-mail to the application log (local development, tests). */
		LOG

	}

	/**
	 * @param apiKey the Brevo API key (secret; from the environment, never committed)
	 * @param baseUrl the Brevo API base URL
	 */
	public record Brevo(String apiKey, String baseUrl) {
	}

}
