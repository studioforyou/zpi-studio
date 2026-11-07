package studioforyou.pl.backend.communication.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link PushSender} selected by {@code app.push.provider}.
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
class PushConfiguration {

	@Bean
	PushSender pushSender(PushProperties properties, @Value("${FIREBASE_PROJECT_ID}") String projectId) {
		return switch (properties.provider()) {
			case FCM -> new FcmPushSender(projectId);
			case LOG -> new LogPushSender();
		};
	}

}
