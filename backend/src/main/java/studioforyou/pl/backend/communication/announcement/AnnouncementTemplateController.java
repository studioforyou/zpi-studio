package studioforyou.pl.backend.communication.announcement;

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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import studioforyou.pl.backend.communication.announcement.AnnouncementTemplateDtos.AnnouncementTemplate;
import studioforyou.pl.backend.communication.announcement.AnnouncementTemplateDtos.CreateTemplateRequest;
import studioforyou.pl.backend.communication.announcement.AnnouncementTemplateDtos.UpdateTemplateRequest;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.ListResponse;

/**
 * Announcement templates: content the administrator writes once and picks when writing a new
 * announcement. Templates are only filled into the form; sending still goes through
 * {@code POST /api/v1/announcements}.
 */
@RestController
@RequestMapping("/api/v1/announcement-templates")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "communication")
class AnnouncementTemplateController {

	private final AnnouncementTemplateService templates;

	AnnouncementTemplateController(AnnouncementTemplateService templates) {
		this.templates = templates;
	}

	@GetMapping
	@Operation(summary = "List announcement templates", operationId = "listAnnouncementTemplates",
			description = "Every template, by name.")
	public ListResponse<AnnouncementTemplate> list() {
		return new ListResponse<>(templates.list());
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get an announcement template", operationId = "getAnnouncementTemplate")
	@ApiResponse(responseCode = "200", description = "The template.")
	@ApiResponse(responseCode = "404", description = "No such template.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public AnnouncementTemplate get(@PathVariable UUID id) {
		return templates.get(id);
	}

	@PostMapping
	@Operation(summary = "Create an announcement template", operationId = "createAnnouncementTemplate")
	@ApiResponse(responseCode = "201", description = "The created template.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: missing or too long name, title or text.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "ANNOUNCEMENT_TEMPLATE_NAME_ALREADY_USED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<AnnouncementTemplate> create(@Valid @RequestBody CreateTemplateRequest request,
			Authentication authentication) {
		AnnouncementTemplate created = templates.create(request, actor(authentication));
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(created.id())
			.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Change an announcement template", operationId = "updateAnnouncementTemplate",
			description = "Omitted fields stay as they are. Announcements already sent from it do not change.")
	@ApiResponse(responseCode = "200", description = "The changed template.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: blank or too long name, title or text.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such template.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "ANNOUNCEMENT_TEMPLATE_NAME_ALREADY_USED", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public AnnouncementTemplate update(@PathVariable UUID id, @Valid @RequestBody UpdateTemplateRequest request,
			Authentication authentication) {
		return templates.update(id, request, actor(authentication));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete an announcement template", operationId = "deleteAnnouncementTemplate",
			description = "Announcements already sent from it stay in the history.")
	@ApiResponse(responseCode = "204", description = "Deleted.")
	@ApiResponse(responseCode = "404", description = "No such template.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		templates.delete(id);
		return ResponseEntity.noContent().build();
	}

	private static UUID actor(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.requireActiveAccountId();
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

}
