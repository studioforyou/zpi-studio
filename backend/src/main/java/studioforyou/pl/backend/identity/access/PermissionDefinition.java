package studioforyou.pl.backend.identity.access;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A permission from the permission dictionary.
 */
@Schema(description = "A permission an account can get from its roles or from an override.")
public record PermissionDefinition(@Schema(example = "SCHEDULE_MANAGE_ALL") String code,
		@Schema(example = "SCHEDULING") String category,
		@Schema(example = "Create and edit all classes in the schedule") String description) {

}
