package studioforyou.pl.backend.identity.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileSummary;
import studioforyou.pl.backend.identity.AuthenticatedAccount;
import studioforyou.pl.backend.identity.security.AccountAuthenticationToken;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;
import studioforyou.pl.backend.staff.StaffProfiles.StaffSummary;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

	private final CustomerProfiles customerProfiles;

	private final StaffProfiles staffProfiles;

	public MeController(CustomerProfiles customerProfiles, StaffProfiles staffProfiles) {
		this.customerProfiles = customerProfiles;
		this.staffProfiles = staffProfiles;
	}

	/**
	 * Returns the account behind the token, whatever its status, so the app can tell
	 * an active account from one that waits for approval or is suspended. A Firebase user
	 * without an account here gets 403 {@code ACCOUNT_NOT_FOUND}.
	 */
	@GetMapping
	public MeResponse me(AccountAuthenticationToken authentication) {
		return authentication.account()
			.map(this::toResponse)
			.orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND,
					"This user has no account in the application."));
	}

	private MeResponse toResponse(AuthenticatedAccount account) {
		Optional<ProfileSummary> ownProfile = customerProfiles.findOwnProfile(account.id());
		Optional<StaffSummary> staffProfile = staffProfiles.findByAccount(account.id());

		List<MeResponse.Profile> profiles = new ArrayList<>();
		ownProfile.ifPresent(own -> {
			profiles.add(toProfile(own, false));
			customerProfiles.findChildrenOf(own.id()).forEach(child -> profiles.add(toProfile(child, true)));
		});

		String displayName = ownProfile.map(ProfileSummary::displayName)
			.or(() -> staffProfile.map(StaffSummary::displayName))
			.orElse(account.email());

		List<String> roles = account.roles().stream().sorted().toList();
		return new MeResponse(account.id(), account.email(), account.status(), displayName, roles,
				staffProfile.map(StaffSummary::id).orElse(null), profiles,
				ownProfile.map(ProfileSummary::id).orElse(null));
	}

	private static MeResponse.Profile toProfile(ProfileSummary profile, boolean isChild) {
		return new MeResponse.Profile(profile.id(), profile.displayName(), isChild);
	}

}
