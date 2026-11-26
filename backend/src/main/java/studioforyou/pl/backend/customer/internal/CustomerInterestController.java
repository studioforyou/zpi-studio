package studioforyou.pl.backend.customer.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.customer.CustomerInterests;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.offering.ServiceTypeSummary;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * The service types the signed-in client, or a child they are a guardian of, wants to hear about:
 * when booking of such a class opens, they get a {@code CLASS_BOOKING_OPENED} notification. Any
 * other profile answers 404, so its existence is not revealed.
 */
@RestController
@PreAuthorize("hasRole('CLIENT')")
@Tag(name = "customer")
@ApiResponse(responseCode = "404", description = "The profile does not exist or the account cannot act for it, "
		+ "or the service type does not exist or is not offered.",
		content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
public class CustomerInterestController {

	private static final String PROFILE = "The profile is the client's own (the activeProfileId from GET /api/v1/me) "
			+ "or a child they are a current guardian of.";

	private final CustomerProfiles profiles;

	private final CustomerInterests interests;

	private final ServiceTypes serviceTypes;

	public CustomerInterestController(CustomerProfiles profiles, CustomerInterests interests,
			ServiceTypes serviceTypes) {
		this.profiles = profiles;
		this.interests = interests;
		this.serviceTypes = serviceTypes;
	}

	@GetMapping("/api/v1/me/customer-profiles/{profileId}/interests")
	@Operation(summary = "List the service types a profile wants to hear about", operationId = "getCustomerInterests",
			description = "Requires an ACTIVE CLIENT. " + PROFILE)
	@ApiResponse(responseCode = "200", description = "The marked service types.")
	public CustomerInterestsResponse list(AccountAuthentication authentication, @PathVariable UUID profileId) {
		return response(actingFor(authentication, profileId));
	}

	@PutMapping("/api/v1/me/customer-profiles/{profileId}/interests/{serviceTypeId}")
	@Operation(summary = "Mark a service type for a profile", operationId = "addCustomerInterest",
			description = "Requires an ACTIVE CLIENT. " + PROFILE + " The service type must be active. Marking it "
					+ "again changes nothing.")
	@ApiResponse(responseCode = "200", description = "The marked service types after the change.")
	public CustomerInterestsResponse add(AccountAuthentication authentication, @PathVariable UUID profileId,
			@PathVariable UUID serviceTypeId) {
		UUID profile = actingFor(authentication, profileId);
		boolean offered = serviceTypes.findById(serviceTypeId).map(ServiceTypeSummary::active).orElse(false);
		if (!offered) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Service type not found.");
		}
		interests.add(profile, serviceTypeId);
		return response(profile);
	}

	@DeleteMapping("/api/v1/me/customer-profiles/{profileId}/interests/{serviceTypeId}")
	@Operation(summary = "Unmark a service type for a profile", operationId = "removeCustomerInterest",
			description = "Requires an ACTIVE CLIENT. " + PROFILE + " Unmarking a service type that is not marked "
					+ "changes nothing.")
	@ApiResponse(responseCode = "200", description = "The marked service types after the change.")
	public CustomerInterestsResponse remove(AccountAuthentication authentication, @PathVariable UUID profileId,
			@PathVariable UUID serviceTypeId) {
		UUID profile = actingFor(authentication, profileId);
		interests.remove(profile, serviceTypeId);
		return response(profile);
	}

	private CustomerInterestsResponse response(UUID profileId) {
		return new CustomerInterestsResponse(interests.findServiceTypes(profileId));
	}

	private UUID actingFor(AccountAuthentication authentication, UUID profileId) {
		if (!profiles.isActingFor(authentication.requireActiveAccountId(), profileId)) {
			throw CustomerPersonalDetails.notFound();
		}
		return profileId;
	}

}
