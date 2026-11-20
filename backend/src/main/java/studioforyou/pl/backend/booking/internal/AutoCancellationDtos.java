package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The automatic cancellation of a group class as administrators see and override it (#136): whether
 * the rule applies, when the participants are counted, and the class forced to run.
 */
final class AutoCancellationDtos {

	private AutoCancellationDtos() {
	}

	/** What the automatic cancellation rule will do with the class. */
	@Schema(name = "AutoCancellationState")
	enum State {

		/** The participants will be counted at {@code checkAt}; too few cancel the class. */
		PENDING,
		/** An administrator forced the class to run; it is not cancelled whatever the participants. */
		FORCED,
		/**
		 * The rule does not apply to the class: it is off for its service or the studio, or switched off
		 * for its fixed group or for the class itself.
		 */
		OFF,
		/** Nothing more happens: the participants were already counted, or the class started or is not scheduled. */
		CLOSED

	}

	@Schema(name = "EventAutoCancellation", description = "The automatic cancellation of a group class for too few "
			+ "participants and how an administrator overrode it.")
	record AutoCancellationResponse(UUID eventId,
			@Schema(description = "PENDING, FORCED, OFF or CLOSED.") State state,
			@Schema(description = "Whether the rule applies to the class: on for its service (or the studio) and not "
					+ "switched off for its fixed group or the class itself. An administrator forcing the class to run "
					+ "does not change it.") boolean ruleEnabled,
			@Schema(description = "An administrator forced the class to run although too few participants booked it.")
			boolean forceRun,
			@Schema(description = "The class's fixed group; null when it has none.") UUID trainingGroupId,
			@Schema(description = "The rule is switched off for every class of the fixed group.") boolean disabledForGroup,
			@Schema(description = "Participants with an active reservation.") int participants,
			@Schema(description = "The fewest participants the class runs with; null when the rule does not apply.")
			Integer minimumParticipants,
			@Schema(description = "When the participants are counted; null when the rule does not apply.")
			Instant checkAt) {
	}

	@Schema(name = "ChangeEventAutoCancellationRequest")
	record ChangeAutoCancellationRequest(
			@Schema(description = "true forces the class to run whatever the participants; false lets the rule decide "
					+ "again.") @NotNull Boolean forceRun) {
	}

}
