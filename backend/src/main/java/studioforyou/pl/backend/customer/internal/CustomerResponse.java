package studioforyou.pl.backend.customer.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles.ParticipantContact;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;

/**
 * A participant profile: an adult client or a child.
 *
 * @param birthDate the date of birth, or {@code null} if unknown
 * @param email for administrators: the address of the participant's account or, for a child, of
 * its guardian's account; {@code null} for trainers or when there is none
 * @param emailOfGuardian whether {@code email} belongs to the child's guardian
 */
@Schema(name = "Customer")
public record CustomerResponse(UUID id, CustomerKind kind, @Schema(example = "Katarzyna") String firstName,
		@Schema(example = "Zielińska") String lastName, @Schema(example = "Katarzyna Zielińska") String displayName,
		LocalDate birthDate, @Schema(example = "katarzyna@example.com") String email, boolean emailOfGuardian) {

	/**
	 * @param contact the participant's contact, or {@code null} when the caller may not see it
	 */
	static CustomerResponse from(ProfileDetails profile, ParticipantContact contact) {
		return new CustomerResponse(profile.id(), profile.kind(), profile.firstName(), profile.lastName(),
				profile.displayName(), profile.birthDate(), (contact != null) ? contact.email() : null,
				contact != null && contact.emailOfGuardian());
	}

}
