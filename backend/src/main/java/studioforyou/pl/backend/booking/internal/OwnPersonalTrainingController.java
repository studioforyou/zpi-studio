package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.CancelRequest;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.DisputeRequest;
import studioforyou.pl.backend.booking.internal.PersonalTrainingDtos.Session;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ListResponse;

@RestController
@RequestMapping("/api/v1/me/profiles/{profileId}/personal-trainings")
@Tag(name = "booking")
class OwnPersonalTrainingController {
	private final PersonalTrainingService service;
	OwnPersonalTrainingController(PersonalTrainingService service) { this.service = service; }

	@GetMapping
	@Operation(summary = "List my reserved personal training sessions", operationId = "listOwnPersonalTrainings")
	ListResponse<Session> list(@PathVariable UUID profileId, @RequestParam Instant from, @RequestParam Instant to,
			AccountAuthentication actor) {
		return new ListResponse<>(service.list(actor.requireActiveAccountId(), profileId, from, to));
	}

	@GetMapping("/{reservationId}")
	@Operation(summary = "Get my personal session and available actions", operationId = "getOwnPersonalTraining")
	Session get(@PathVariable UUID profileId, @PathVariable UUID reservationId, AccountAuthentication actor) {
		return service.get(actor.requireActiveAccountId(), profileId, reservationId);
	}

	@PostMapping("/{reservationId}/cancel")
	@Operation(summary = "Cancel my personal session after confirming its accounting consequence", operationId = "cancelOwnPersonalTraining")
	Session cancel(@PathVariable UUID profileId, @PathVariable UUID reservationId, @Valid @RequestBody CancelRequest request,
			AccountAuthentication actor) {
		return service.cancel(actor.requireActiveAccountId(), profileId, reservationId, request);
	}

	@PostMapping("/{reservationId}/disputes")
	@Operation(summary = "Report a discrepancy within the studio's personal-session dispute window", operationId = "raiseOwnPersonalTrainingDispute")
	Session dispute(@PathVariable UUID profileId, @PathVariable UUID reservationId, @Valid @RequestBody DisputeRequest request,
			AccountAuthentication actor) {
		return service.raiseDispute(actor.requireActiveAccountId(), profileId, reservationId, request);
	}
}
