package studioforyou.pl.backend.identity.accounts;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One profile of an account: its own client profile, its staff profile or a child it is the guardian
 * of. Fields a kind of profile does not have are {@code null}.
 *
 * @param phone of the client or staff profile
 * @param bio of the staff profile, the description clients see
 * @param birthDate of a child
 * @param emergencyContactName of a child
 * @param emergencyPhone of a child
 */
@Schema(name = "AccountProfile")
public record AccountProfile(UUID id, Kind kind, String firstName, String lastName, @Nullable String phone,
		@Nullable String bio, @Nullable LocalDate birthDate, @Nullable String emergencyContactName,
		@Nullable String emergencyPhone) {

	public enum Kind {

		CLIENT, STAFF, CHILD

	}

}
