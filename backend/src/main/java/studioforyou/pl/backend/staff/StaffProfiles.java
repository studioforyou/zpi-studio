package studioforyou.pl.backend.staff;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

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

	/** The given staff members, whatever their status; unknown ids are left out. */
	List<StaffMember> findAllById(Collection<UUID> ids);

	/**
	 * Staff members sorted by last and first name.
	 *
	 * @param active {@code true} for active staff only, {@code false} for inactive only, {@code null} for all
	 */
	List<StaffMember> findAll(@Nullable Boolean active);

	/**
	 * The service types each of the given staff members is qualified to run. Staff members
	 * without any qualification are missing from the map.
	 */
	Map<UUID, Set<UUID>> findQualifications(Collection<UUID> staffIds);

	record StaffSummary(UUID id, String displayName) {

	}

	record StaffProfile(UUID id, @NotBlank @Size(max = 80) String firstName,
			@NotBlank @Size(max = 80) String lastName, @Size(max = 20) String phone) {

	}

	/** Presence is separate from value so omitted fields and explicit nulls stay distinct. */
	record ProfileChanges(boolean firstNamePresent, String firstName, boolean lastNamePresent,
			String lastName, boolean phonePresent, String phone) {

	}

	/**
	 * A staff member as other modules see them.
	 *
	 * @param calendarColor the colour of the staff member in calendars, {@code #RRGGBB}, or {@code null}
	 * @param active whether the profile has status {@code ACTIVE}
	 */
	record StaffMember(UUID id, String firstName, String lastName, @Nullable String calendarColor,
			boolean active) {

		public String displayName() {
			return firstName + " " + lastName;
		}

	}

}
