package studioforyou.pl.backend.customer.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.customer.CustomerKind;

@Schema(name = "CustomerProfileResponse", description = "Personal and contact details of a participant profile.")
public record CustomerProfileResponse(UUID id,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ADULT for the account's own profile, CHILD for a child of a guardian.") CustomerKind kind,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80) String firstName,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80) String lastName,
		@Schema(nullable = true) LocalDate birthDate,
		@Schema(maxLength = 20, nullable = true) String phone,
		@Schema(maxLength = 120, nullable = true) String emergencyContactName,
		@Schema(maxLength = 20, nullable = true) String emergencyPhone) {

	static CustomerProfileResponse from(PersonalDetails details) {
		return new CustomerProfileResponse(details.id(), details.kind(), details.firstName(), details.lastName(),
				details.birthDate(), details.phone(), details.emergencyContactName(), details.emergencyPhone());
	}

}
