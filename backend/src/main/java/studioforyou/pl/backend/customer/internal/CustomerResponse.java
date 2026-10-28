package studioforyou.pl.backend.customer.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;

/**
 * A participant profile: an adult client or a child.
 *
 * @param birthDate the date of birth, or {@code null} if unknown
 */
@Schema(name = "Customer")
public record CustomerResponse(UUID id, CustomerKind kind, @Schema(example = "Katarzyna") String firstName,
		@Schema(example = "Zielińska") String lastName, @Schema(example = "Katarzyna Zielińska") String displayName,
		LocalDate birthDate) {

	static CustomerResponse from(ProfileDetails profile) {
		return new CustomerResponse(profile.id(), profile.kind(), profile.firstName(), profile.lastName(),
				profile.displayName(), profile.birthDate());
	}

}
