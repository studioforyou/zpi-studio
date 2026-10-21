package studioforyou.pl.backend.identity.web;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import studioforyou.pl.backend.identity.AccountStatus;

/**
 * The signed-in account, as returned by {@code GET /api/v1/me}.
 *
 * @param accountId the account id
 * @param email the login e-mail
 * @param status the account status; only {@code ACTIVE} accounts can use the app
 * @param displayName the name shown in the app
 * @param roles role codes, e.g. {@code CLIENT}, {@code TRAINER}
 * @param staffProfileId the account's staff (trainer) profile, or {@code null} without one
 * @param profiles customer profiles the account can act for: its own and its children's
 * @param activeProfileId the profile the app starts with, or {@code null} without a customer profile
 */
public record MeResponse(UUID accountId, String email, AccountStatus status, String displayName,
		List<String> roles, UUID staffProfileId, List<Profile> profiles, UUID activeProfileId) {

	/**
	 * @param isChild whether this is a child the account is a guardian of, not its own profile
	 */
	public record Profile(UUID id, String displayName, @JsonProperty("isChild") boolean isChild) {
	}

}
