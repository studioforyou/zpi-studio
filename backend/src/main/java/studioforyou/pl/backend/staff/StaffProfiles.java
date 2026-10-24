package studioforyou.pl.backend.staff;

import java.util.Optional;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public staff profile operations for other modules. Callers authorize the account before
 * requesting its profile; storage and normalization belong to the staff module.
 */
public interface StaffProfiles {

	/** The account's staff profile, if it has one. */
	Optional<StaffSummary> findByAccount(UUID accountId);

	/** The account's personal and contact details, or {@code NOT_FOUND} without a profile. */
	StaffProfile getByAccount(UUID accountId);

	/**
	 * Changes only supplied fields. Trims surrounding Unicode whitespace before validation.
	 * Names must remain nonblank and at most 80 characters; a null or blank phone clears it.
	 */
	StaffProfile updateByAccount(UUID accountId, ProfileChanges changes);

	/** Creates the staff profile of a new account and returns its id. */
	UUID create(UUID accountId, String firstName, String lastName);

	record StaffSummary(UUID id, String displayName) {

	}

	record StaffProfile(UUID id, @NotBlank @Size(max = 80) String firstName,
			@NotBlank @Size(max = 80) String lastName, @Size(max = 20) String phone) {

	}

	/** Presence is separate from value so omitted fields and explicit nulls stay distinct. */
	record ProfileChanges(boolean firstNamePresent, String firstName, boolean lastNamePresent,
			String lastName, boolean phonePresent, String phone) {

	}

}
