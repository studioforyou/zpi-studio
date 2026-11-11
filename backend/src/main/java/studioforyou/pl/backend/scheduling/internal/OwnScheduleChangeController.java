package studioforyou.pl.backend.scheduling.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.scheduling.internal.ScheduleChangeNotices.NoticeResponse;
import studioforyou.pl.backend.scheduling.internal.ScheduleChangeNotices.Owner;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.ListResponse;
import studioforyou.pl.backend.staff.StaffProfiles;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The changes of their events a participant or a trainer has not closed yet: one notice per saved
 * change of the time, the rooms or the staff. A participant's notices are read for their own
 * profile or a child's; a trainer's for the staff profile of the account.
 */
@RestController
@Tag(name = "scheduling")
class OwnScheduleChangeController {

	private static final String PROFILE = "/api/v1/me/profiles/{profileId}/schedule-changes";

	private static final String STAFF = "/api/v1/me/schedule-changes";

	private final ScheduleChangeNotices notices;

	private final CustomerProfiles customers;

	private final StaffProfiles staffProfiles;

	OwnScheduleChangeController(ScheduleChangeNotices notices, CustomerProfiles customers,
			StaffProfiles staffProfiles) {
		this.notices = notices;
		this.customers = customers;
		this.staffProfiles = staffProfiles;
	}

	@GetMapping(PROFILE)
	@Operation(summary = "List the changes of my classes", operationId = "listOwnScheduleChanges",
			description = "Open notices of changes of the participant's booked and waited-for events that have "
					+ "not ended, newest first.")
	@ApiResponse(responseCode = "404", description = "The account may not act for this participant.")
	ListResponse<NoticeResponse> list(@PathVariable UUID profileId, Authentication authentication) {
		return new ListResponse<>(this.notices.open(customer(authentication, profileId), Instant.now()));
	}

	@PostMapping(PROFILE + "/{noticeId}/dismiss")
	@Operation(summary = "Close a change of my class", operationId = "dismissOwnScheduleChange")
	@ApiResponse(responseCode = "204", description = "Closed.")
	@ApiResponse(responseCode = "404", description = "No such open notice of this participant.")
	ResponseEntity<Void> dismiss(@PathVariable UUID profileId, @PathVariable UUID noticeId,
			Authentication authentication) {
		return dismissed(this.notices.dismiss(customer(authentication, profileId), noticeId, Instant.now()));
	}

	@PostMapping(PROFILE + "/dismiss-all")
	@Operation(summary = "Close all changes of my classes", operationId = "dismissAllOwnScheduleChanges")
	@ApiResponse(responseCode = "204", description = "Closed.")
	ResponseEntity<Void> dismissAll(@PathVariable UUID profileId, Authentication authentication) {
		this.notices.dismissAll(customer(authentication, profileId), Instant.now());
		return ResponseEntity.noContent().build();
	}

	@PostMapping(PROFILE + "/seen")
	@Operation(summary = "Mark the changes of my classes seen", operationId = "markOwnScheduleChangesSeen",
			description = "The participant opened their schedule; the notices stay open but are no longer new.")
	@ApiResponse(responseCode = "204", description = "Marked.")
	ResponseEntity<Void> seen(@PathVariable UUID profileId, Authentication authentication) {
		this.notices.markSeen(customer(authentication, profileId), Instant.now());
		return ResponseEntity.noContent().build();
	}

	@GetMapping(STAFF)
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "List the changes of my events as a trainer", operationId = "listOwnStaffScheduleChanges",
			description = "Open notices of changes of events the trainer works at, was assigned to or was taken "
					+ "off, that have not ended, newest first.")
	ListResponse<NoticeResponse> listStaff(Authentication authentication) {
		return new ListResponse<>(this.notices.open(staff(authentication), Instant.now()));
	}

	@PostMapping(STAFF + "/{noticeId}/dismiss")
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "Close a change of my event as a trainer", operationId = "dismissOwnStaffScheduleChange")
	@ApiResponse(responseCode = "204", description = "Closed.")
	@ApiResponse(responseCode = "404", description = "No such open notice of this trainer.")
	ResponseEntity<Void> dismissStaff(@PathVariable UUID noticeId, Authentication authentication) {
		return dismissed(this.notices.dismiss(staff(authentication), noticeId, Instant.now()));
	}

	@PostMapping(STAFF + "/dismiss-all")
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "Close all changes of my events as a trainer",
			operationId = "dismissAllOwnStaffScheduleChanges")
	@ApiResponse(responseCode = "204", description = "Closed.")
	ResponseEntity<Void> dismissAllStaff(Authentication authentication) {
		this.notices.dismissAll(staff(authentication), Instant.now());
		return ResponseEntity.noContent().build();
	}

	@PostMapping(STAFF + "/seen")
	@PreAuthorize("hasRole('TRAINER')")
	@Operation(summary = "Mark the changes of my events seen as a trainer",
			operationId = "markOwnStaffScheduleChangesSeen")
	@ApiResponse(responseCode = "204", description = "Marked.")
	ResponseEntity<Void> seenStaff(Authentication authentication) {
		this.notices.markSeen(staff(authentication), Instant.now());
		return ResponseEntity.noContent().build();
	}

	private Owner customer(Authentication authentication, UUID profileId) {
		if (!this.customers.isActingFor(CurrentAccount.of(authentication).id(), profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		return Owner.customer(profileId);
	}

	private Owner staff(Authentication authentication) {
		return Owner.staff(this.staffProfiles.getByAccount(CurrentAccount.of(authentication).id()).id());
	}

	private static ResponseEntity<Void> dismissed(boolean closed) {
		if (!closed) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Schedule change not found.");
		}
		return ResponseEntity.noContent().build();
	}

}
