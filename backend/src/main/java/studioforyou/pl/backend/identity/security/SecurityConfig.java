package studioforyou.pl.backend.identity.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Every request except the public ones needs a valid Firebase ID token
 * ({@code Authorization: Bearer <token>}) of an account with status {@code ACTIVE}.
 * {@code GET /api/v1/me} only needs a valid token, so the app can learn the account's
 * status (for example that it still waits for approval). Reading and accepting an e-mail
 * invitation is public: the secret token in the link is the proof. Sending one needs the
 * {@code ADMIN} role (roles are granted to active accounts only).
 * <p>
 * 401 and 403 responses are passed to the MVC exception handling, so they get the same
 * {@code application/problem+json} body with a {@code code} as every other error.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, FirebaseJwtAuthenticationConverter converter,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		AuthenticationEntryPoint unauthenticated = (request, response, ex) -> exceptionResolver
			.resolveException(request, response, null, ex);
		AccessDeniedHandler accessDenied = (request, response, ex) -> exceptionResolver.resolveException(request,
				response, null, ex);
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/error").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/ping", "/actuator/health").permitAll()
				.requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/invitations").hasRole("ADMIN")
				.requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/invitations/*/accept").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
				.anyRequest().hasAuthority(FirebaseJwtAuthenticationConverter.ACTIVE_ACCOUNT))
			.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthenticated)
				.accessDeniedHandler(accessDenied))
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
				.authenticationEntryPoint(unauthenticated)
				.accessDeniedHandler(accessDenied))
			.build();
	}

}
