package studioforyou.pl.backend.trainings.internal;

import java.net.URI;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.AddHealthNoteRequest;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.HealthNoteResponse;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.TraineeCardResponse;
import studioforyou.pl.backend.trainings.internal.TraineeCardDtos.UpdateTraineeCardRequest;
import studioforyou.pl.backend.trainings.internal.TraineeCardService.Trainer;

/**
 * The trainee card the signed-in trainer keeps for a participant they currently work with. The
 * trainer and their permissions come from the account, never from the request.
 */
@RestController
@RequestMapping("/api/v1/me/trainees/{customerId}")
@PreAuthorize("hasRole('TRAINER')")
@Tag(name = "trainings")
@ApiResponse(responseCode = "404", description = "No such participant, the participant is not currently assigned "
		+ "to the trainer or is not active, or the account has no staff profile.", content = @Content(
				mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class TraineeCardController {

	private final TraineeCardService cards;

	TraineeCardController(TraineeCardService cards) {
		this.cards = cards;
	}

	@GetMapping("/card")
	@Operation(summary = "Get my trainee's card", operationId = "getOwnTraineeCard",
			description = "Goals, sport experience and, with access to the participant's health data, their current "
					+ "and archived health notes. A participant has one card, shared by all of their current "
					+ "trainers. healthAccess tells whether the notes are shown: GRANTED needs the "
					+ "CUSTOMER_HEALTH_READ permission and the participant's consent on this trainer's assignment.")
	@ApiResponse(responseCode = "200", description = "The trainee's card; version 0 and empty texts while nobody has "
			+ "filled it in.")
	TraineeCardResponse card(@PathVariable UUID customerId, AccountAuthentication authentication) {
		return cards.get(customerId, trainer(authentication));
	}

	@PatchMapping("/card")
	@Operation(summary = "Edit my trainee's goals and sport experience", operationId = "updateOwnTraineeCard",
			description = "Omitted fields stay unchanged; null or blank text clears a field. version is the card's "
					+ "version the change is based on (0 for a card nobody has filled in yet). A body with only the "
					+ "version changes nothing. Health notes are added and archived separately.")
	@ApiResponse(responseCode = "200", description = "The saved card.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: a text is longer than 2000 characters or "
			+ "version is missing.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT: someone changed the card in the meantime.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	TraineeCardResponse updateCard(@PathVariable UUID customerId,
			@Valid @RequestBody UpdateTraineeCardRequest request, AccountAuthentication authentication) {
		return cards.update(customerId, trainer(authentication), request);
	}

	@PostMapping("/health-notes")
	@Operation(summary = "Add a health note to my trainee's card", operationId = "addOwnTraineeHealthNote",
			description = "Records a current health restriction or injury reported by the signed-in trainer, e.g. "
					+ "one the participant told them about. Notes are never edited; one that no longer applies is "
					+ "archived.")
	@ApiResponse(responseCode = "201", description = "The added note.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: kind or description is missing, or the "
			+ "description is longer than 1000 characters.", content = @Content(
					mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED without the CUSTOMER_HEALTH_READ permission, "
			+ "or HEALTH_DATA_NOT_SHARED when the participant has not shared their health data with the trainer.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<HealthNoteResponse> addHealthNote(@PathVariable UUID customerId,
			@Valid @RequestBody AddHealthNoteRequest request, AccountAuthentication authentication) {
		HealthNoteResponse note = cards.addHealthNote(customerId, trainer(authentication), request);
		return ResponseEntity.created(URI.create("/api/v1/me/trainees/" + customerId + "/health-notes/" + note.id()))
			.body(note);
	}

	@GetMapping("/health-notes/{noteId}")
	@Operation(summary = "Get a health note of my trainee", operationId = "getOwnTraineeHealthNote")
	@ApiResponse(responseCode = "200", description = "The note, current or archived.")
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED or HEALTH_DATA_NOT_SHARED, as when adding a note.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	HealthNoteResponse healthNote(@PathVariable UUID customerId, @PathVariable UUID noteId,
			AccountAuthentication authentication) {
		return cards.getHealthNote(customerId, noteId, trainer(authentication));
	}

	@PostMapping("/health-notes/{noteId}/archive")
	@Operation(summary = "Archive a health note that no longer applies", operationId = "archiveOwnTraineeHealthNote",
			description = "The note stays on the card as history, with who archived it and when. Archiving an already "
					+ "archived note returns it unchanged.")
	@ApiResponse(responseCode = "200", description = "The archived note.")
	@ApiResponse(responseCode = "403", description = "ACCESS_DENIED or HEALTH_DATA_NOT_SHARED, as when adding a note.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	HealthNoteResponse archiveHealthNote(@PathVariable UUID customerId, @PathVariable UUID noteId,
			AccountAuthentication authentication) {
		return cards.archiveHealthNote(customerId, noteId, trainer(authentication));
	}

	private static Trainer trainer(AccountAuthentication authentication) {
		boolean healthPermission = authentication.getAuthorities()
			.stream()
			.anyMatch((authority) -> TraineeCardService.HEALTH_PERMISSION.equals(authority.getAuthority()));
		return new Trainer(authentication.requireActiveAccountId(), healthPermission);
	}

}
