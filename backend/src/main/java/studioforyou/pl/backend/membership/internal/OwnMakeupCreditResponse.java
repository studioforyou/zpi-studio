package studioforyou.pl.backend.membership.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** One unused make-up credit, valid strictly before its expiry instant. */
@Schema(name = "OwnMakeupCredit")
public record OwnMakeupCreditResponse(UUID id, UUID sourcePassId, String productName, Instant grantedAt,
		Instant expiresAt) {

}
