package studioforyou.pl.backend.identity.accounts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * New personal and contact details of an account; replaces the current ones.
 *
 * @param email the login; changing it also changes the e-mail of the linked Firebase user
 * @param firstName first name, saved in every profile of the account
 * @param lastName last name, saved in every profile of the account
 * @param phone phone number; {@code null} or blank clears it
 */
public record UpdateAccountRequest(@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(max = 80) String firstName, @NotBlank @Size(max = 80) String lastName,
		@Nullable @Size(max = 20) @Pattern(regexp = "^[+0-9 ()-]*$") String phone) {
}
