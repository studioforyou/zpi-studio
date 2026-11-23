package studioforyou.pl.backend.booking.internal;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.membership.BookingEntitlements.EligiblePass;
import studioforyou.pl.backend.scheduling.PersonalTrainingPlanning.Issue;
import studioforyou.pl.backend.scheduling.PersonalTrainingPlanning.Plan;
import studioforyou.pl.backend.shared.api.AccountAuthentication;

@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/personal-trainings")
@Tag(name = "booking")
class PersonalTrainingPlanningController {

	private final PersonalTrainingPlanningService service;

	PersonalTrainingPlanningController(PersonalTrainingPlanningService service) { this.service = service; }

	@PostMapping("/preview")
	@Operation(summary = "Check a personal session, participant requirements and eligible passes without saving",
			operationId = "previewOwnPersonalTrainingPlan")
	Preview preview(@Valid @RequestBody PreviewRequest request, AccountAuthentication actor) {
		return service.preview(actor.requireActiveAccountId(), request);
	}

	@PostMapping
	@Operation(summary = "Plan my private personal session and book its participant", operationId = "createOwnPersonalTraining",
			description = "Creates the session, reservation, pass allocation and any new trainer cooperation atomically. "
					+ "Checks the trainer, room, participant, age, documents and pass. The trainer comes from the signed-in account.")
	ResponseEntity<Created> create(@Valid @RequestBody CreateRequest request, AccountAuthentication actor) {
		Created result = service.create(actor.requireActiveAccountId(), request);
		return ResponseEntity.created(URI.create("/api/v1/me/schedule-events/" + result.eventId())).body(result);
	}

	@Schema(name = "PersonalTrainingPlanRequest")
	record PreviewRequest(@NotNull UUID customerId, @NotNull UUID serviceTypeId, @NotNull UUID resourceId,
			@NotNull Instant startsAt, @NotNull Instant endsAt) {
		Plan plan() { return new Plan(serviceTypeId, resourceId, startsAt, endsAt); }
	}

	@Schema(name = "CreatePersonalTrainingRequest")
	record CreateRequest(@NotNull @Valid PreviewRequest plan, @NotNull UUID customerPassId) {}

	@Schema(name = "PersonalTrainingPlanPreview")
	record Preview(List<Issue> issues, String participantReason, List<EligiblePass> eligiblePasses, boolean startsCooperation) {}

	@Schema(name = "CreatedPersonalTraining")
	record Created(UUID eventId, UUID reservationId) {}
}
