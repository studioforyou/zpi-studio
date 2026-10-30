package studioforyou.pl.backend.staff.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.UserText;
import studioforyou.pl.backend.staff.StaffProfiles;

/** Authenticates the owner and delegates profile rules and storage to staff. */
@RestController
@RequestMapping("/api/v1/me/staff-profile")
@PreAuthorize("hasRole('TRAINER')")
@Tag(name = "staff")
@ApiResponse(responseCode = "404", description = "The account has no staff profile.",
		content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
public class StaffProfileController {

	private final StaffProfiles staffProfiles;

	public StaffProfileController(StaffProfiles staffProfiles) {
		this.staffProfiles = staffProfiles;
	}

	@GetMapping
	@Operation(summary = "Get the signed-in trainer's profile", operationId = "getMyStaffProfile",
			description = "Requires an ACTIVE application account with the TRAINER role. The owner comes from the verified Firebase token.")
	@ApiResponse(responseCode = "200", description = "The trainer's profile.")
	public StaffProfileResponse get(AccountAuthentication authentication) {
		return StaffProfileResponse.from(staffProfiles.getByAccount(authentication.requireActiveAccountId()));
	}

	@PatchMapping
	@Operation(summary = "Edit the signed-in trainer's profile", operationId = "updateMyStaffProfile",
			description = "Requires an ACTIVE TRAINER. Omitted fields stay unchanged. Surrounding Unicode whitespace is trimmed. "
					+ "Names must be nonblank and at most 80 characters; null names are invalid. Phone has at most 20 characters; "
					+ "null, empty text or whitespace clears it. An empty object keeps the current values. Only the staff profile changes. "
					+ UserText.CHARACTER_RULES)
	@ApiResponse(responseCode = "200", description = "The saved trainer profile.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED for invalid fields, or MALFORMED_REQUEST for an invalid body.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public StaffProfileResponse update(AccountAuthentication authentication,
			@RequestBody UpdateStaffProfileRequest request) {
		return StaffProfileResponse.from(
				staffProfiles.updateByAccount(authentication.requireActiveAccountId(), request.toChanges()));
	}

}
