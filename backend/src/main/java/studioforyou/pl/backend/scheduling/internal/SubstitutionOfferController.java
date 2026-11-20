package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.Action;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.Offer;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/substitution-offers")
@PreAuthorize("hasRole('TRAINER')")
class SubstitutionOfferController {

	private final SubstitutionManagement requests;
	private final StaffProfiles staff;

	SubstitutionOfferController(SubstitutionManagement requests, StaffProfiles staff) {
		this.requests = requests; this.staff = staff;
	}

	@GetMapping
	@Operation(summary = "List available substitutions or my offers to take them", operationId = "listSubstitutionOffers")
	ListResponse<Offer> list(@RequestParam Instant from, @RequestParam Instant to,
			@RequestParam(defaultValue = "false") boolean mine, Authentication auth) {
		ScheduleEventController.requireValidWindow(from, to);
		return new ListResponse<>(this.requests.offers(staffId(auth), from, to, mine));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Read a substitution without participant or private reason data", operationId = "getSubstitutionOffer")
	Offer get(@PathVariable UUID id, Authentication auth) { return this.requests.offer(id, staffId(auth)); }

	@PostMapping("/{id}/claim")
	@Operation(summary = "Offer to take the substitution", operationId = "claimSubstitution")
	Offer claim(@PathVariable UUID id, @Valid @RequestBody Action request, Authentication auth) {
		return this.requests.claim(id, staffId(auth), CurrentAccount.of(auth).id(), request);
	}

	@PostMapping("/{id}/withdraw")
	@Operation(summary = "Withdraw my pending claim and reopen the offer", operationId = "withdrawSubstitutionClaim")
	Offer withdraw(@PathVariable UUID id, @Valid @RequestBody Action request, Authentication auth) {
		return this.requests.withdraw(id, staffId(auth), CurrentAccount.of(auth).id(), request);
	}

	private UUID staffId(Authentication auth) { return this.staff.getByAccount(CurrentAccount.of(auth).id()).id(); }
}
