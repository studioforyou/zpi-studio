package studioforyou.pl.backend.identity.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileSummary;
import studioforyou.pl.backend.identity.AuthenticatedAccount;
import studioforyou.pl.backend.identity.security.AccountAuthenticationToken;
import studioforyou.pl.backend.staff.StaffProfiles;

@RestController
@RequestMapping("/api")
public class MeController {

	/** {@code code} of the 403 response when the Firebase user has no account here. */
	public static final String ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND";

	private final CustomerProfiles customerProfiles;

	private final StaffProfiles staffProfiles;

	public MeController(CustomerProfiles customerProfiles, StaffProfiles staffProfiles) {
		this.customerProfiles = customerProfiles;
		this.staffProfiles = staffProfiles;
	}

	/**
	 * Returns the account behind the token, whatever its status, so the app can tell
	 * an active account from one that waits for approval or is suspended.
	 */
	@GetMapping("/me")
	public ResponseEntity<?> me(AccountAuthenticationToken authentication) {
		return authentication.account()
			.<ResponseEntity<?>>map(account -> ResponseEntity.ok(toResponse(account)))
			.orElseGet(MeController::accountNotFound);
	}

	private MeResponse toResponse(AuthenticatedAccount account) {
		Optional<ProfileSummary> ownProfile = customerProfiles.findOwnProfile(account.id());

		List<MeResponse.Profile> profiles = new ArrayList<>();
		ownProfile.ifPresent(own -> {
			profiles.add(toProfile(own));
			customerProfiles.findChildrenOf(own.id()).forEach(child -> profiles.add(toProfile(child)));
		});

		String displayName = ownProfile.map(ProfileSummary::displayName)
			.or(() -> staffProfiles.findDisplayName(account.id()))
			.orElse(account.email());

		List<String> roles = account.roles().stream().sorted().toList();
		return new MeResponse(account.id(), account.email(), account.status(), displayName, roles, profiles,
				ownProfile.map(ProfileSummary::id).orElse(null));
	}

	private static MeResponse.Profile toProfile(ProfileSummary profile) {
		return new MeResponse.Profile(profile.id(), profile.displayName());
	}

	private static ResponseEntity<ProblemDetail> accountNotFound() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
				"This user has no account in the application.");
		problem.setProperty("code", ACCOUNT_NOT_FOUND);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
	}

}
