package studioforyou.pl.backend.identity.access;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A role from the role dictionary with its default permissions.
 */
@Schema(description = "A role with the permissions it gives by default.")
public record RoleDefinition(@Schema(example = "TRAINER") String code, @Schema(example = "Trener") String displayName,
		@Schema(example = "[\"SCHEDULE_EDIT_OWN\", \"AVAILABILITY_EDIT_OWN\"]") List<String> permissions) {

}
