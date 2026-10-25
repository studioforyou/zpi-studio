package studioforyou.pl.backend.configuration;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Versioned service rules. Null fields inherit studio defaults; no event or reservation
 * is changed by this contract. Time windows are measured before the event starts.
 */
public interface BookingPolicies {

	Values studioDefaults();

	Map<UUID, Definition> findForServices(Collection<ServiceContext> services);

	/** The caller serializes edits to the service. Null overrides restore studio defaults. */
	void replaceForService(ServiceContext service, Values overrides, UUID changedBy);

	record ServiceContext(UUID id, boolean groupRulesAllowed, Integer minParticipants, Integer maxParticipants) {
	}

	@Schema(name = "ServiceBookingPolicy")
	record Definition(UUID policyId, UUID studioPolicyId, Values overrides, Values effective) {
	}

	@Schema(name = "BookingPolicyValues")
	record Values(Boolean cancellationAllowed,
			@Min(0) @Max(Short.MAX_VALUE) Integer freeCancelHours,
			Boolean lateCancelAllowed, Boolean lateCancelForfeitsEntry,
			@Min(0) @Max(Short.MAX_VALUE) Integer bookingOpensDaysBefore,
			@Min(0) @Max(Short.MAX_VALUE) Integer bookingClosesMinutesBefore,
			Boolean waitlistEnabled,
			@Min(0) @Max(Short.MAX_VALUE) Integer waitlistConfirmMinutes,
			Boolean autoCancelEnabled,
			@Min(1) @Max(Short.MAX_VALUE) Integer autoCancelMinParticipants,
			@Min(0) @Max(Short.MAX_VALUE) Integer autoCancelHoursBefore) {
	}

}
