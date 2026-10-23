package studioforyou.pl.backend.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param password the new account password (8 to 128 characters)
 */
public record AcceptInvitationRequest(@NotBlank @Size(min = 8, max = 128) String password) {
}
