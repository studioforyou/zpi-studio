package studioforyou.pl.backend.communication.push;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Push settings ({@code app.push.*}).
 *
 * @param provider which {@link PushSender} to use
 */
@Validated
@ConfigurationProperties("app.push")
public record PushProperties(@NotNull Provider provider) {

	public enum Provider {

		/** Sends through Firebase Cloud Messaging with the Firebase Admin SDK. */
		FCM,

		/** Only writes the notification to the application log (local development, tests). */
		LOG

	}

}
