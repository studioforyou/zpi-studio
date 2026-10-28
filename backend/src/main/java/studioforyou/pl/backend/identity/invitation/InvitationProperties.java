package studioforyou.pl.backend.identity.invitation;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Invitation settings ({@code app.invitations.*}).
 *
 * @param linkBaseUrl address of the page that opens an invitation; the token is added as {@code ?token=}
 * @param validFor how long an invitation link works
 */
@Validated
@ConfigurationProperties("app.invitations")
record InvitationProperties(@NotBlank String linkBaseUrl, @NotNull Duration validFor) {
}
