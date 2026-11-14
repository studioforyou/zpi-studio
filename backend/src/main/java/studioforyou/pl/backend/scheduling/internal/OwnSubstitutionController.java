package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.SubstitutionRequests.CreateRequest;
import studioforyou.pl.backend.scheduling.internal.SubstitutionRequests.RequestResponse;
import studioforyou.pl.backend.scheduling.internal.SubstitutionRequests.RequestState;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The trainer comes from the signed-in account, never from a supplied staff id. */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@Tag(name = "scheduling")
class OwnSubstitutionController {

	private static final String EVENTS = "/api/v1/me/schedule-events/{eventId}/substitution-request";
	private static final String REQUESTS = "/api/v1/me/substitution-requests";

	private final SubstitutionRequests requests;
	private final StaffProfiles staff;

	OwnSubstitutionController(SubstitutionRequests requests, StaffProfiles staff) {
		this.requests = requests;
		this.staff = staff;
	}

	@GetMapping(REQUESTS)
	@Operation(summary = "List my active substitution requests in a schedule window",
			operationId = "listOwnSubstitutionRequests")
	ListResponse<RequestResponse> list(@RequestParam Instant from, @RequestParam Instant to,
			Authentication authentication) {
		ScheduleEventController.requireValidWindow(from, to);
		return new ListResponse<>(this.requests.list(staffId(authentication), from, to));
	}

	@GetMapping(EVENTS)
	@Operation(summary = "Get the substitution state of one of my events", operationId = "getOwnSubstitutionState",
			description = "An assistant can see the status but cannot request or withdraw a substitution. "
					+ "A reason is visible only to the original trainer.")
	RequestState state(@PathVariable UUID eventId, Authentication authentication) {
		return this.requests.state(eventId, staffId(authentication));
	}

	@PostMapping(EVENTS)
	@Operation(summary = "Request a substitute for an event I lead", operationId = "createOwnSubstitutionRequest",
			description = "For an upcoming scheduled event only. Reserves no resources and changes neither the "
					+ "event's staff nor its bookings. Only one active request per event is allowed.")
	@ApiResponse(responseCode = "201", description = "Created.")
	@ApiResponse(responseCode = "409", description = "SUBSTITUTION_ALREADY_REQUESTED.")
	ResponseEntity<RequestResponse> create(@PathVariable UUID eventId, @Valid @RequestBody CreateRequest input,
			Authentication authentication) {
		return ResponseEntity.status(HttpStatus.CREATED).body(this.requests.create(eventId, staffId(authentication),
				CurrentAccount.of(authentication).id(), input));
	}

	@PostMapping(REQUESTS + "/{requestId}/cancel")
	@Operation(summary = "Withdraw my open substitution request", operationId = "cancelOwnSubstitutionRequest")
	@ApiResponse(responseCode = "204", description = "Withdrawn; the request remains in the database.")
	@ApiResponse(responseCode = "409", description = "SUBSTITUTION_NOT_OPEN, e.g. already claimed or withdrawn.")
	ResponseEntity<Void> cancel(@PathVariable UUID requestId, Authentication authentication) {
		this.requests.cancel(requestId, staffId(authentication));
		return ResponseEntity.noContent().build();
	}

	private UUID staffId(Authentication authentication) {
		return this.staff.getByAccount(CurrentAccount.of(authentication).id()).id();
	}

}
