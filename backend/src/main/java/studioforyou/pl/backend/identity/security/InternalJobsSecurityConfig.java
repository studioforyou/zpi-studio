package studioforyou.pl.backend.identity.security;

import java.util.List;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * {@code /internal/**} is called by Cloud Scheduler, not by users. A request needs a Google-signed
 * OIDC ID token ({@code Authorization: Bearer <token>}) that Cloud Scheduler issues for the
 * scheduler's service account: issuer {@code https://accounts.google.com}, audience
 * {@code app.jobs.oidc.audience} and e-mail {@code app.jobs.oidc.service-account}. Firebase user
 * tokens are not accepted here, and Google tokens are not accepted anywhere else. Without both
 * settings (e.g. locally) every request is rejected.
 */
@Configuration
class InternalJobsSecurityConfig {

	@Bean
	@Order(1)
	SecurityFilterChain internalJobsFilterChain(HttpSecurity http, InternalJobTokens tokens,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		AuthenticationEntryPoint unauthenticated = (request, response, ex) -> exceptionResolver
			.resolveException(request, response, null, ex);
		AccessDeniedHandler accessDenied = (request, response, ex) -> exceptionResolver.resolveException(request,
				response, null, ex);
		return http
			.securityMatcher("/internal/**")
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
			.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthenticated)
				.accessDeniedHandler(accessDenied))
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.decoder(tokens::decode))
				.authenticationEntryPoint(unauthenticated)
				.accessDeniedHandler(accessDenied))
			.build();
	}

	/**
	 * Verifies Cloud Scheduler's OIDC tokens. A bean of its own type, not a {@code JwtDecoder}, so
	 * that it does not replace the Firebase decoder of the user API.
	 */
	@Component
	static class InternalJobTokens {

		static final String GOOGLE_ISSUER = "https://accounts.google.com";

		static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

		private final NimbusJwtDecoder decoder;

		private final boolean configured;

		InternalJobTokens(@Value("${app.jobs.oidc.audience:}") String audience,
				@Value("${app.jobs.oidc.service-account:}") String serviceAccount) {
			this.configured = !audience.isBlank() && !serviceAccount.isBlank();
			this.decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build();
			this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
					JwtValidators.createDefaultWithIssuer(GOOGLE_ISSUER),
					claim("aud", audience, (jwt) -> jwt.getAudience() != null && jwt.getAudience().contains(audience)),
					claim("email", serviceAccount, (jwt) -> serviceAccount.equals(jwt.getClaimAsString("email"))
							&& Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))))));
		}

		Jwt decode(String token) {
			if (!configured) {
				throw new BadJwtException("Internal jobs are not configured (app.jobs.oidc.*).");
			}
			return decoder.decode(token);
		}

		private static OAuth2TokenValidator<Jwt> claim(String name, String expected,
				Predicate<Jwt> check) {
			OAuth2Error error = new OAuth2Error("invalid_token", "The token's " + name + " is not " + expected, null);
			return (jwt) -> check.test(jwt) ? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(error);
		}

	}

}
