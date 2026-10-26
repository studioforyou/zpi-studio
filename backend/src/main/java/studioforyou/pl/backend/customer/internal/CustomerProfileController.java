package studioforyou.pl.backend.customer.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileSummary;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Personal details of the signed-in client's own profile and of the children they are a
 * guardian of. Any other profile answers 404, so its existence is not revealed.
 */
@RestController
@PreAuthorize("hasRole('CLIENT')")
@Tag(name = "customer")
@ApiResponse(responseCode = "404", description = "The profile does not exist or the account cannot act for it.",
		content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
public class CustomerProfileController {

	private static final String UPDATE_RULES = "Omitted fields stay unchanged. Surrounding Unicode whitespace is trimmed. "
			+ "Names must be nonblank and at most 80 characters; null names are invalid. A null birth date, or null, "
			+ "empty or whitespace phone and emergency contact values clear them. The emergency contact name and "
			+ "phone must be set together.";

	private final CustomerProfiles profiles;

	private final CustomerPersonalDetails details;

	public CustomerProfileController(CustomerProfiles profiles, CustomerPersonalDetails details) {
		this.profiles = profiles;
		this.details = details;
	}

	@GetMapping("/api/v1/me/customer-profile")
	@Operation(summary = "Get the signed-in client's own profile", operationId = "getMyCustomerProfile",
			description = "Requires an ACTIVE application account with the CLIENT role.")
	@ApiResponse(responseCode = "200", description = "The client's own profile.")
	public CustomerProfileResponse getOwn(AccountAuthentication authentication) {
		return CustomerProfileResponse.from(details.get(ownProfileId(authentication)));
	}

	@PatchMapping("/api/v1/me/customer-profile")
	@Operation(summary = "Edit the signed-in client's own profile", operationId = "updateMyCustomerProfile",
			description = "Requires an ACTIVE CLIENT. " + UPDATE_RULES)
	@ApiResponse(responseCode = "200", description = "The saved profile.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED for invalid fields, or MALFORMED_REQUEST for an invalid body.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CustomerProfileResponse updateOwn(AccountAuthentication authentication,
			@RequestBody UpdateCustomerProfileRequest request) {
		return CustomerProfileResponse.from(details.update(ownProfileId(authentication), request));
	}

	@GetMapping("/api/v1/me/customer-profiles/{profileId}")
	@Operation(summary = "Get a profile the client acts for", operationId = "getCustomerProfile",
			description = "The client's own profile or a child they are a current guardian of.")
	@ApiResponse(responseCode = "200", description = "The profile.")
	public CustomerProfileResponse get(AccountAuthentication authentication, @PathVariable UUID profileId) {
		return CustomerProfileResponse.from(details.get(actingFor(authentication, profileId)));
	}

	@PatchMapping("/api/v1/me/customer-profiles/{profileId}")
	@Operation(summary = "Edit a profile the client acts for", operationId = "updateCustomerProfile",
			description = "The client's own profile or a child they are a current guardian of. " + UPDATE_RULES)
	@ApiResponse(responseCode = "200", description = "The saved profile.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED for invalid fields, or MALFORMED_REQUEST for an invalid body.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CustomerProfileResponse update(AccountAuthentication authentication, @PathVariable UUID profileId,
			@RequestBody UpdateCustomerProfileRequest request) {
		return CustomerProfileResponse.from(details.update(actingFor(authentication, profileId), request));
	}

	private UUID ownProfileId(AccountAuthentication authentication) {
		return profiles.findOwnProfile(authentication.requireActiveAccountId())
			.map(ProfileSummary::id)
			.orElseThrow(CustomerPersonalDetails::notFound);
	}

	private UUID actingFor(AccountAuthentication authentication, UUID profileId) {
		if (!profiles.isActingFor(authentication.requireActiveAccountId(), profileId)) {
			throw CustomerPersonalDetails.notFound();
		}
		return profileId;
	}

}
