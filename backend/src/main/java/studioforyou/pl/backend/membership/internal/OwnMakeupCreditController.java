package studioforyou.pl.backend.membership.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;

/** Available make-up credits of the account's own profile or its children. */
@RestController
@RequestMapping("/api/v1/me/profiles/{profileId}/makeup-credits")
@Tag(name = "membership")
public class OwnMakeupCreditController {

	private final MakeupCreditQueries credits;

	OwnMakeupCreditController(MakeupCreditQueries credits) {
		this.credits = credits;
	}

	@GetMapping
	@Operation(summary = "List my available make-up credits", operationId = "listOwnMakeupCredits",
			description = "Unused AVAILABLE credits whose expiry instant is still in the future, soonest expiry first. "
					+ "totalItems is the total available count across all pages. The source pass may already have expired. "
					+ "Eligibility for a specific class is checked separately when booking.")
	@ApiResponse(responseCode = "200", description = "Available credits and their exact expiry instants in UTC.")
	@ApiResponse(responseCode = "404", description = "No active profile, or the account does not act for it.")
	public PageResponse<OwnMakeupCreditResponse> list(@PathVariable UUID profileId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, Authentication authentication) {
		return credits.list(actor(authentication), profileId, page, size);
	}

	private static UUID actor(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.activeAccountId()
				.orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED, "An active account is required."));
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

}
