package studioforyou.pl.backend.identity.web;

import java.util.List;
import java.util.UUID;

import studioforyou.pl.backend.identity.AccountStatus;

/**
 * The signed-in account, as returned by {@code GET /api/me}.
 *
 * @param accountId the account id
 * @param email the login e-mail
 * @param status the account status; only {@code ACTIVE} accounts can use the app
 * @param displayName the name shown in the app
 * @param roles role codes, e.g. {@code CLIENT}, {@code TRAINER}
 * @param profiles customer profiles the account can act for: its own and its children's
 * @param activeProfileId the profile the app starts with, or {@code null} without a customer profile
 */
public record MeResponse(UUID accountId, String email, AccountStatus status, String displayName,
		List<String> roles, List<Profile> profiles, UUID activeProfileId) {

	public record Profile(UUID id, String displayName) {
	}

}
