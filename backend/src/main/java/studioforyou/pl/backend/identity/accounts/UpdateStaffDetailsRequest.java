package studioforyou.pl.backend.identity.accounts;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.shared.api.UserText;

/**
 * New details of an account's staff profile; replaces the current ones.
 *
 * @param firstName first name, also saved in the account's own client profile
 * @param lastName last name, also saved in the account's own client profile
 * @param phone phone number of the staff profile only; {@code null} or blank clears it
 * @param bio the description clients see; {@code null} or blank clears it
 */
@Schema(name = "UpdateAccountStaffProfileRequest")
public record UpdateStaffDetailsRequest(
		@NotBlank @Size(max = 80) @Pattern(regexp = UserText.PERSON_NAME) String firstName,
		@NotBlank @Size(max = 80) @Pattern(regexp = UserText.PERSON_NAME) String lastName,
		@Nullable @Size(max = 20) @Pattern(regexp = "(" + UserText.PHONE + ")?") String phone,
		@Nullable @Size(max = 2000) String bio) {
}
