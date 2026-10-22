package studioforyou.pl.backend.communication.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Creates the {@link EmailSender} selected by {@code app.mail.provider}.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
class MailConfiguration {

	@Bean
	EmailSender emailSender(MailProperties properties, RestClient.Builder restClientBuilder) {
		return switch (properties.provider()) {
			case BREVO -> {
				if (properties.brevo() == null || properties.brevo().apiKey() == null
						|| properties.brevo().apiKey().isBlank()) {
					throw new IllegalStateException("app.mail.provider=BREVO needs app.mail.brevo.api-key (BREVO_API_KEY)");
				}
				yield new BrevoEmailSender(restClientBuilder, properties);
			}
			case LOG -> new LogEmailSender();
		};
	}

}
