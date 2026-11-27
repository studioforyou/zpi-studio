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

	/** Resolves the current studio, service, fixed-group and occurrence layers in that order. */
	Definition forOccurrence(UUID serviceTypeId, UUID trainingGroupId, UUID eventId);

	/**
	 * The longest {@code bookingOpensDaysBefore} any current policy sets, studio defaults included:
	 * no class's booking opens earlier than this many days before its start.
	 */
	int longestBookingOpensDaysBefore();

	/** The caller serializes edits to the service. Null overrides restore studio defaults. */
	void replaceForService(ServiceContext service, Values overrides, UUID changedBy);

	/**
	 * Whether the automatic cancellation is switched off for every class of the fixed group by the
	 * group's current policy.
	 */
	boolean isAutoCancelDisabledForGroup(UUID trainingGroupId);

	/**
	 * Switches the automatic cancellation off for every class of the fixed group ({@code disabled}),
	 * or back to the rule the group's classes inherit. The group's other rules are kept; a change
	 * closes the group's current policy and creates its next version. The caller checks the group
	 * exists and serializes edits to it.
	 *
	 * @return whether the policy changed
	 */
	boolean changeGroupAutoCancel(UUID trainingGroupId, boolean disabled, UUID changedBy);

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
