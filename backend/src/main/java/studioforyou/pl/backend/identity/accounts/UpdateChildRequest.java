package studioforyou.pl.backend.identity.accounts;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.shared.api.UserText;

/**
 * New details of a child an account is the guardian of; replaces the current ones. Blank optional
 * values clear them.
 *
 * @param emergencyContactName given together with {@code emergencyPhone}, or neither
 */
@Schema(name = "UpdateAccountChildRequest")
public record UpdateChildRequest(@NotBlank @Size(max = 80) @Pattern(regexp = UserText.PERSON_NAME) String firstName,
		@NotBlank @Size(max = 80) @Pattern(regexp = UserText.PERSON_NAME) String lastName,
		@Nullable @Past LocalDate birthDate,
		@Nullable @Size(max = 120) @Pattern(regexp = UserText.PERSON_NAME) String emergencyContactName,
		@Nullable @Size(max = 20) @Pattern(regexp = "(" + UserText.PHONE + ")?") String emergencyPhone) {

	@AssertTrue(message = "needs both a name and a phone number, or neither")
	@Schema(hidden = true)
	public boolean isEmergencyContactComplete() {
		return (UserText.trimToNull(emergencyContactName) == null) == (UserText.trimToNull(emergencyPhone) == null);
	}

}
