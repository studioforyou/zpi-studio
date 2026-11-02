package studioforyou.pl.backend.booking.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ListResponse;

/**
 * Participants of the signed-in trainer's events, so the trainer can prepare for the group. The
 * event itself is served by scheduling under the same path.
 */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/schedule-events/{eventId}/participants")
@Tag(name = "booking")
class OwnEventParticipantController {

	private final EventRosterService rosters;

	OwnEventParticipantController(EventRosterService rosters) {
		this.rosters = rosters;
	}

	@GetMapping
	@Operation(summary = "List participants of my event", operationId = "listOwnEventParticipants",
			description = "Participants with an active reservation for an event the signed-in trainer runs or "
					+ "assists, sorted by last and first name. Cancelled reservations are left out.")
	@ApiResponse(responseCode = "200", description = "The event's participants.")
	@ApiResponse(responseCode = "404", description = "No such event, the trainer does not run or assist it, it is a "
			+ "block, or the account has no staff profile.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	ListResponse<EventParticipantResponse> list(@PathVariable UUID eventId, AccountAuthentication authentication) {
		UUID accountId = authentication.activeAccountId()
			.orElseThrow(() -> new AccessDeniedException("An active application account is required."));
		return new ListResponse<>(this.rosters.participants(accountId, eventId));
	}

}
