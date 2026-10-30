package studioforyou.pl.backend.identity.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email the new administrator's login
 * @param firstName first name, used in the staff profile
 * @param lastName last name, used in the staff profile
 */
public record CreateAdminRequest(@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(max = 80) String firstName, @NotBlank @Size(max = 80) String lastName) {
}
