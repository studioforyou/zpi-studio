package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Passes of the profiles the signed-in account acts for: its own customer profile and the
 * children it is a guardian of.
 */
@RestController
@RequestMapping("/api/v1/me/profiles/{profileId}/passes")
@Tag(name = "membership")
public class OwnPassController {

	private final CustomerPassService passes;

	private final StudioSettings studio;

	public OwnPassController(CustomerPassService passes, StudioSettings studio) {
		this.passes = passes;
		this.studio = studio;
	}

	@GetMapping
	@Operation(summary = "List my passes", operationId = "listOwnPasses",
			description = "Passes of the account's own profile or of a child it is a guardian of, newest first, "
					+ "without cancelled ones. The status is the one today: during a suspension a pass is SUSPENDED, "
						+ "past its last day it is EXPIRED. "
						+ "expiringSoon marks an active or suspended pass with at most 7 days or at most 2 entries left. "
						+ "renewalDeclined marks a pass the participant said they will not renew.")
	@ApiResponse(responseCode = "200", description = "The profile's passes.")
	@ApiResponse(responseCode = "404", description = "No such profile, or the account does not act for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public OwnPassListResponse list(@PathVariable UUID profileId, Authentication authentication) {
		LocalDate today = studio.today();
		List<CustomerPass> own = passes.listOwn(CurrentAccount.of(authentication).id(), profileId);
		Map<UUID, List<PassSuspension>> suspensions = passes.suspensionsOf(own);
		return new OwnPassListResponse(own.stream()
			.map((pass) -> OwnPassResponse.from(pass, suspensions.getOrDefault(pass.getId(), List.of()), today))
			.toList());
	}

	@PutMapping("/{passId}/renewal-decline")
	@Operation(summary = "Decline renewing my pass", operationId = "declineOwnPassRenewal",
			description = "Says the participant will not renew the pass: no more reminders to buy the next one "
					+ "(PASS_EXPIRING, PASS_LAST_ENTRY_USED) are sent for it. Only for a pass that is active, "
					+ "suspended or scheduled. Declining again changes nothing.")
	@ApiResponse(responseCode = "200", description = "The pass, with renewalDeclined set.")
	@ApiResponse(responseCode = "404", description = "No such profile or pass, or the account does not act for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "PASS_NOT_USABLE: the pass is expired, cancelled or unpaid.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public OwnPassResponse declineRenewal(@PathVariable UUID profileId, @PathVariable UUID passId,
			Authentication authentication) {
		return response(passes.declineRenewal(CurrentAccount.of(authentication).id(), profileId, passId));
	}

	@DeleteMapping("/{passId}/renewal-decline")
	@Operation(summary = "Undo declining the renewal of my pass", operationId = "undoOwnPassRenewalDecline",
			description = "Reminders to buy the next pass are sent again. Only for a pass that is active, "
					+ "suspended or scheduled. Undoing without a decision changes nothing.")
	@ApiResponse(responseCode = "200", description = "The pass, with renewalDeclined cleared.")
	@ApiResponse(responseCode = "404", description = "No such profile or pass, or the account does not act for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "PASS_NOT_USABLE: the pass is expired, cancelled or unpaid.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public OwnPassResponse undoRenewalDecline(@PathVariable UUID profileId, @PathVariable UUID passId,
			Authentication authentication) {
		return response(passes.undoRenewalDecline(CurrentAccount.of(authentication).id(), profileId, passId));
	}

	private OwnPassResponse response(CustomerPass pass) {
		List<PassSuspension> suspensions = passes.suspensionsOf(List.of(pass)).getOrDefault(pass.getId(), List.of());
		return OwnPassResponse.from(pass, suspensions, studio.today());
	}

}
