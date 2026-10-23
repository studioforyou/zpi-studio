package studioforyou.pl.backend.customer.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Participant profiles for the studio staff, e.g. to choose who a pass is assigned to.
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
	@PreAuthorize("hasRole('ADMIN')")
	@Operation(summary = "Search active participants", operationId = "searchCustomers",
			description = "Adults and children, sorted by last and first name.")
	public PageResponse<CustomerResponse> search(
			@Parameter(description = "Part of the first and/or last name, or of the phone number; all participants when omitted.")
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return profiles.searchActive(query, page, size).map(CustomerResponse::from);
	}

}
