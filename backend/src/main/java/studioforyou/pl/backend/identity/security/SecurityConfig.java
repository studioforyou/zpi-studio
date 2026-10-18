package studioforyou.pl.backend.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every request except the public ones needs a valid Firebase ID token
 * ({@code Authorization: Bearer <token>}) of an account with status {@code ACTIVE}.
 * {@code GET /api/me} only needs a valid token, so the app can learn the account's
 * status (for example that it still waits for approval).
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, FirebaseJwtAuthenticationConverter converter)
			throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> requests
				.requestMatchers("/error").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/ping").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/me").authenticated()
				.anyRequest().hasAuthority(FirebaseJwtAuthenticationConverter.ACTIVE_ACCOUNT))
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
			.build();
	}

}
