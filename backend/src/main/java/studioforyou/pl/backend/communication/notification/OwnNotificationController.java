package studioforyou.pl.backend.communication.notification;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.PageResponse;

/** The signed-in account's in-app notification inbox. */
@RestController
@RequestMapping("/api/v1/me/notifications")
@Tag(name = "communication")
class OwnNotificationController {

	private static final String ROLE_DESCRIPTION = "Only notifications about this role (and older ones without a role), "
			+ "for an account that uses the app as a client and as a trainer.";

	private final NotificationStore store;

	OwnNotificationController(NotificationStore store) {
		this.store = store;
	}

	@GetMapping
	@Operation(summary = "List my notifications", operationId = "listOwnNotifications",
			description = "Due notifications, newest first, read and unread.")
	@ApiResponse(responseCode = "200", description = "A page of notifications.")
	public PageResponse<NotificationResponse> list(
			@Parameter(description = ROLE_DESCRIPTION) @RequestParam(required = false) NotificationRole role,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, Authentication authentication) {
		PageResponse<NotificationStore.InboxItem> items = store.inbox(actor(authentication), role, Instant.now(), page,
				size);
		return PageResponse.of(items.items().stream().map(NotificationResponse::from).toList(), items.page(),
				items.size(), items.totalItems());
	}

	@GetMapping("/unread-count")
	@Operation(summary = "Count my unread notifications", operationId = "countOwnUnreadNotifications")
	@ApiResponse(responseCode = "200", description = "The number of due unread notifications, for a badge.")
	public UnreadCountResponse unreadCount(
			@Parameter(description = ROLE_DESCRIPTION) @RequestParam(required = false) NotificationRole role,
			Authentication authentication) {
		return new UnreadCountResponse(store.unreadCount(actor(authentication), role, Instant.now()));
	}

	@PostMapping("/{notificationId}/read")
	@Operation(summary = "Mark my notification read", operationId = "markOwnNotificationRead",
			description = "Idempotent: an already read notification keeps its first read time.")
	@ApiResponse(responseCode = "200", description = "The notification, now read.")
	@ApiResponse(responseCode = "404", description = "No such due notification of this account.")
	public NotificationResponse markRead(@PathVariable UUID notificationId, Authentication authentication) {
		return store.markRead(actor(authentication), notificationId, Instant.now())
			.map(NotificationResponse::from)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification not found."));
	}

	@PostMapping("/read-all")
	@Operation(summary = "Mark all my notifications read", operationId = "markAllOwnNotificationsRead")
	@ApiResponse(responseCode = "200", description = "How many notifications were marked read.")
	public MarkedReadResponse markAllRead(
			@Parameter(description = ROLE_DESCRIPTION) @RequestParam(required = false) NotificationRole role,
			Authentication authentication) {
		return new MarkedReadResponse(store.markAllRead(actor(authentication), role, Instant.now()));
	}

	private static UUID actor(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.activeAccountId()
				.orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED, "An active account is required."));
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

	/** @param count due unread notifications */
	public record UnreadCountResponse(long count) {
	}

	/** @param markedRead notifications that were unread until now */
	public record MarkedReadResponse(int markedRead) {
	}

}
