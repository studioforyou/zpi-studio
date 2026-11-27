package studioforyou.pl.backend.communication.announcement;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AnnouncementDetails;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AnnouncementPreview;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AnnouncementSummary;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.AudienceRequest;
import studioforyou.pl.backend.communication.announcement.AnnouncementDtos.SendAnnouncementRequest;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;

/**
 * Announcements the administrator sends to one person, a training group, the participants of a
 * class or all clients. They arrive in the app's notification inbox and as a push.
 */
@RestController
@RequestMapping("/api/v1/announcements")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "communication")
class AnnouncementController {

	private final AnnouncementService announcements;

	AnnouncementController(AnnouncementService announcements) {
		this.announcements = announcements;
	}

	@PostMapping("/preview")
	@Operation(summary = "Count who an announcement would reach", operationId = "previewAnnouncement",
			description = "Nothing is sent. For a marketing announcement it also counts the people left out for lack "
					+ "of the MARKETING_PUSH consent.")
	@ApiResponse(responseCode = "200", description = "The figures.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: missing or unknown target, or a class that "
			+ "is cancelled or not in the next 30 days.")
	public AnnouncementPreview preview(@Valid @RequestBody AudienceRequest request) {
		return announcements.preview(request);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Send an announcement", operationId = "sendAnnouncement",
			description = "Creates one in-app notification per recipient (a child's go to its guardians) and pushes "
					+ "them in the background. A sent announcement cannot be changed or withdrawn.")
	@ApiResponse(responseCode = "201", description = "The sent announcement.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: missing title or text, too long, or an "
			+ "invalid target.")
	@ApiResponse(responseCode = "422", description = "ANNOUNCEMENT_NO_RECIPIENTS: nobody would get it.")
	public AnnouncementSummary send(@Valid @RequestBody SendAnnouncementRequest request, Authentication authentication) {
		return announcements.send(request, actor(authentication));
	}

	@GetMapping
	@Operation(summary = "List sent announcements", operationId = "listAnnouncements",
			description = "Newest first, with how many people got, read and were pushed each one.")
	public PageResponse<AnnouncementSummary> list(@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return announcements.history(page, size);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a sent announcement with its recipients", operationId = "getAnnouncement")
	@ApiResponse(responseCode = "200", description = "The announcement and each recipient's status.")
	@ApiResponse(responseCode = "404", description = "No such announcement.")
	public AnnouncementDetails get(@PathVariable UUID id) {
		return announcements.details(id);
	}

	private static UUID actor(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.requireActiveAccountId();
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

}
