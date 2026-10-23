package studioforyou.pl.backend.identity.web;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.staff.StaffProfiles.StaffProfile;

@Schema(name = "StaffProfileResponse", description = "The signed-in trainer's personal and contact details.")
public record StaffProfileResponse(UUID id,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80) String firstName,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 80) String lastName,
		@Schema(maxLength = 20, nullable = true) String phone) {

	static StaffProfileResponse from(StaffProfile profile) {
		return new StaffProfileResponse(profile.id(), profile.firstName(), profile.lastName(), profile.phone());
	}

}
