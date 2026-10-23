package studioforyou.pl.backend.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.staff.StaffProfiles.ProfileChanges;

/** Partial update with explicit presence tracking, including a nullable phone. */
@Schema(name = "UpdateStaffProfileRequest", description = "Omitted fields stay unchanged. Values are trimmed before validation.")
public class UpdateStaffProfileRequest {

	private String firstName;

	private boolean firstNamePresent;

	private String lastName;

	private boolean lastNamePresent;

	private String phone;

	private boolean phonePresent;

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

	@Schema(description = "At most 20 characters after trimming. Null, empty text or whitespace clears the phone.",
			maxLength = 20, nullable = true)
	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phonePresent = true;
		this.phone = phone;
	}

	ProfileChanges toChanges() {
		return new ProfileChanges(firstNamePresent, firstName, lastNamePresent, lastName, phonePresent, phone);
	}

}
