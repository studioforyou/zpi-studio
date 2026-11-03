package studioforyou.pl.backend.membership.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A pass found by a pass search, with the participant holding it.
 */
@Schema(name = "HeldPass")
public record HeldPassResponse(PassHolderResponse holder, CustomerPassResponse pass) {
}
