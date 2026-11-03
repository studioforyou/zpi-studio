package studioforyou.pl.backend.customer.internal;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.shared.api.UserText;

/** The editable personal and contact details of a participant profile, validated before saving. */
record PersonalDetails(UUID id, CustomerKind kind,
		@NotBlank @Size(max = 80) @Pattern(regexp = UserText.PERSON_NAME) String firstName,
		@NotBlank @Size(max = 80) @Pattern(regexp = UserText.PERSON_NAME) String lastName,
		@Past @Nullable LocalDate birthDate,
		@Size(max = 20) @Pattern(regexp = UserText.PHONE) @Nullable String phone,
		@Size(max = 120) @Pattern(regexp = UserText.PERSON_NAME) @Nullable String emergencyContactName,
		@Size(max = 20) @Pattern(regexp = UserText.PHONE) @Nullable String emergencyPhone) {

	/** An emergency contact needs both a name and a phone number, or neither. */
	@AssertTrue
	public boolean isEmergencyContactComplete() {
		return (emergencyContactName == null) == (emergencyPhone == null);
	}

}
