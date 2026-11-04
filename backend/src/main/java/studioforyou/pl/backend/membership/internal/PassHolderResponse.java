package studioforyou.pl.backend.membership.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles.ParticipantContact;

/**
 * The participant holding a pass, with the e-mail address that tells participants with the same
 * name apart.
 *
 * @param email the address of the participant's account or, for a child, of its guardian's
 * account; {@code null} when there is none
 * @param emailOfGuardian whether {@code email} belongs to the child's guardian
 */
@Schema(name = "PassHolder")
public record PassHolderResponse(UUID id, CustomerKind kind, @Schema(example = "Katarzyna") String firstName,
		@Schema(example = "Zielińska") String lastName, @Schema(example = "Katarzyna Zielińska") String displayName,
		@Schema(example = "katarzyna@example.com") String email, boolean emailOfGuardian) {

	static PassHolderResponse from(ParticipantContact contact) {
		return new PassHolderResponse(contact.id(), contact.kind(), contact.firstName(), contact.lastName(),
				contact.displayName(), contact.email(), contact.emailOfGuardian());
	}

}
