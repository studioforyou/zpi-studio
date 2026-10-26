package studioforyou.pl.backend.customer.internal;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** Partial update with explicit presence tracking, so omitted fields and explicit nulls stay distinct. */
@Schema(name = "UpdateCustomerProfileRequest",
		description = "Omitted fields stay unchanged. Values are trimmed before validation.")
public class UpdateCustomerProfileRequest {

	private String firstName;

	private boolean firstNamePresent;

	private String lastName;

	private boolean lastNamePresent;

	private LocalDate birthDate;

	private boolean birthDatePresent;

	private String phone;

	private boolean phonePresent;

	private String emergencyContactName;

	private boolean emergencyContactNamePresent;

	private String emergencyPhone;

	private boolean emergencyPhonePresent;

	@Schema(description = "Nonblank first name, at most 80 characters after trimming. Null is invalid.", maxLength = 80)
	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstNamePresent = true;
		this.firstName = firstName;
	}

	@Schema(description = "Nonblank last name, at most 80 characters after trimming. Null is invalid.", maxLength = 80)
	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastNamePresent = true;
		this.lastName = lastName;
	}

	@Schema(description = "A date in the past. Null clears it.", nullable = true)
	public LocalDate getBirthDate() {
		return birthDate;
	}

	public void setBirthDate(LocalDate birthDate) {
		this.birthDatePresent = true;
		this.birthDate = birthDate;
	}

	@Schema(description = "Digits with an optional leading plus, spaces, dashes or parentheses; at most 20 characters "
			+ "after trimming. Null, empty text or whitespace clears it.", maxLength = 20, nullable = true)
	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phonePresent = true;
		this.phone = phone;
	}

	@Schema(description = "At most 120 characters after trimming. Null, empty text or whitespace clears it. "
			+ "Must be set together with emergencyPhone.", maxLength = 120, nullable = true)
	public String getEmergencyContactName() {
		return emergencyContactName;
	}

	public void setEmergencyContactName(String emergencyContactName) {
		this.emergencyContactNamePresent = true;
		this.emergencyContactName = emergencyContactName;
	}

	@Schema(description = "Same format as phone. Must be set together with emergencyContactName.", maxLength = 20,
			nullable = true)
	public String getEmergencyPhone() {
		return emergencyPhone;
	}

	public void setEmergencyPhone(String emergencyPhone) {
		this.emergencyPhonePresent = true;
		this.emergencyPhone = emergencyPhone;
	}

	/** Applies the supplied fields to the current details, trimming text and clearing blank optional values. */
	PersonalDetails applyTo(PersonalDetails current) {
		return new PersonalDetails(current.id(), current.kind(),
				firstNamePresent ? Texts.trim(firstName) : current.firstName(),
				lastNamePresent ? Texts.trim(lastName) : current.lastName(),
				birthDatePresent ? birthDate : current.birthDate(),
				phonePresent ? Texts.trimToNull(phone) : current.phone(),
				emergencyContactNamePresent ? Texts.trimToNull(emergencyContactName) : current.emergencyContactName(),
				emergencyPhonePresent ? Texts.trimToNull(emergencyPhone) : current.emergencyPhone());
	}

}
