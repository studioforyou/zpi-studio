package studioforyou.pl.backend.membership.internal;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
					+ "without cancelled ones. The status is the one today: past its last day a pass is EXPIRED.")
	@ApiResponse(responseCode = "200", description = "The profile's passes.")
	@ApiResponse(responseCode = "404", description = "No such profile, or the account does not act for it.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public OwnPassListResponse list(@PathVariable UUID profileId, Authentication authentication) {
		LocalDate today = studio.today();
		return new OwnPassListResponse(passes.listOwn(CurrentAccount.of(authentication).id(), profileId)
			.stream()
			.map((pass) -> OwnPassResponse.from(pass, today))
			.toList());
	}

}
