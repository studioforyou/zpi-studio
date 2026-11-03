package studioforyou.pl.backend.identity.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email the client's e-mail; the new account's login
 * @param firstName first name, used in the e-mail and the profile
 * @param lastName last name, used in the profile
 */
public record InviteTraineeRequest(@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(max = 80) String firstName, @NotBlank @Size(max = 80) String lastName) {
}
