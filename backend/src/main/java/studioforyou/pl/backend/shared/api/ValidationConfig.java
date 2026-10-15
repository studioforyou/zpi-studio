package studioforyou.pl.backend.shared.api;

import java.util.Locale;

import jakarta.validation.MessageInterpolator;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps constraint messages in English regardless of the request's {@code Accept-Language}
 * (the API is English; clients show Polish text based on the error codes).
 */
@Configuration(proxyBeanMethods = false)
public class ValidationConfig {

	@Bean
	ValidationConfigurationCustomizer englishValidationMessages() {
		return (configuration) -> configuration
			.messageInterpolator(new FixedLocaleMessageInterpolator(configuration.getDefaultMessageInterpolator()));
	}

	/**
	 * Uses the default (English) message bundle for every request.
	 */
	private record FixedLocaleMessageInterpolator(MessageInterpolator delegate) implements MessageInterpolator {

		@Override
		public String interpolate(String messageTemplate, Context context) {
			return this.delegate.interpolate(messageTemplate, context, Locale.ROOT);
		}

		@Override
		public String interpolate(String messageTemplate, Context context, Locale locale) {
			return this.delegate.interpolate(messageTemplate, context, Locale.ROOT);
		}

	}

}
