package studioforyou.pl.backend.customer.internal;

import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ParticipantContact;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Participant profiles for the studio staff, e.g. to choose who a pass is assigned to or who a
 * trainer starts working with.
 */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "customer")
public class CustomerController {

	private final CustomerProfiles profiles;

	public CustomerController(CustomerProfiles profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('ADMIN', 'TRAINER')")
	@Operation(summary = "Search active participants", operationId = "searchCustomers",
			description = "Adults and children, sorted by last and first name. Trainers use it to choose "
					+ "participants they start working with. Administrators also find participants by e-mail "
					+ "address and get the address to tell participants with the same name apart.")
	public PageResponse<CustomerResponse> search(
			@Parameter(description = "Part of the first and/or last name, or of the phone number; for administrators "
					+ "also of the e-mail address (a child matches its guardian's address). All participants when omitted.")
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, Authentication authentication) {
		boolean admin = authentication.getAuthorities()
			.stream()
			.anyMatch((authority) -> "ROLE_ADMIN".equals(authority.getAuthority()));
		PageResponse<ProfileDetails> found = profiles.searchActive(query, admin, page, size);
		if (!admin) {
			return found.map((profile) -> CustomerResponse.from(profile, null));
		}
		Map<UUID, ParticipantContact> contacts = profiles
			.contacts(found.items().stream().map(ProfileDetails::id).toList());
		return found.map((profile) -> CustomerResponse.from(profile, contacts.get(profile.id())));
	}

}
