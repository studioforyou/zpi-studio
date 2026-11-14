package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.SubstitutionDtos.*;
import studioforyou.pl.backend.shared.api.ListResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@PreAuthorize("hasRole('ADMIN')")
class SubstitutionAdminController {

	private static final String REQUESTS = "/api/v1/substitution-requests";
	private final SubstitutionManagement requests;
	private final StudioEventCancellation cancellations;

	SubstitutionAdminController(SubstitutionManagement requests, StudioEventCancellation cancellations) {
		this.requests = requests; this.cancellations = cancellations;
	}

	@GetMapping(REQUESTS)
	@Operation(summary = "List substitution requests for the studio", operationId = "listStudioSubstitutions")
	ListResponse<Offer> list(@RequestParam Instant from, @RequestParam Instant to) {
		ScheduleEventController.requireValidWindow(from, to);
		return new ListResponse<>(this.requests.admin(from, to));
	}

	@GetMapping(REQUESTS + "/{id}")
	@Operation(summary = "Read a studio substitution request", operationId = "getStudioSubstitution")
	Offer get(@PathVariable UUID id) { return this.requests.adminOffer(id); }

	@GetMapping(REQUESTS + "/{id}/candidates")
	@Operation(summary = "List candidates with current availability", operationId = "listSubstitutionCandidates")
	ListResponse<Candidate> candidates(@PathVariable UUID id) { return new ListResponse<>(this.requests.candidates(id)); }

	@PostMapping(REQUESTS + "/{id}/approve")
	@Operation(summary = "Approve the proposed substitute", operationId = "approveSubstitution")
	Offer approve(@PathVariable UUID id, @Valid @RequestBody Action input, Authentication auth) {
		return this.requests.approve(id, CurrentAccount.of(auth).id(), input);
	}

	@PostMapping(REQUESTS + "/{id}/reject")
	@Operation(summary = "Reject this claim and reopen the offer", operationId = "rejectSubstitution")
	Offer reject(@PathVariable UUID id, @Valid @RequestBody Action input, Authentication auth) {
		return this.requests.reject(id, CurrentAccount.of(auth).id(), input);
	}

	@PostMapping(REQUESTS + "/{id}/assign")
	@Operation(summary = "Assign a checked replacement directly", operationId = "assignSubstitution")
	Offer assign(@PathVariable UUID id, @Valid @RequestBody Assignment input, Authentication auth) {
		return this.requests.assign(id, CurrentAccount.of(auth).id(), input);
	}

	@PostMapping(REQUESTS + "/{id}/close")
	@Operation(summary = "Close the request, leaving the event in its schedule", operationId = "closeSubstitution")
	Offer close(@PathVariable UUID id, @Valid @RequestBody Action input, Authentication auth) {
		return this.requests.close(id, CurrentAccount.of(auth).id(), input);
	}

	@PostMapping("/api/v1/schedule-events/{eventId}/cancel")
	@Operation(summary = "Cancel a future studio event and settle its reservations", operationId = "cancelStudioEvent")
	void cancel(@PathVariable UUID eventId, @Valid @RequestBody Cancellation input, Authentication auth) {
		this.cancellations.cancel(eventId, input, CurrentAccount.of(auth).id());
	}
}
