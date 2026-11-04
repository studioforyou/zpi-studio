package studioforyou.pl.backend.membership.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * @param version the pass's {@code version}; a stale value returns 409 {@code VERSION_CONFLICT}
 */
@Schema(name = "CancelPassSuspensionRequest")
public record CancelSuspensionRequest(@NotNull Long version) {
}
