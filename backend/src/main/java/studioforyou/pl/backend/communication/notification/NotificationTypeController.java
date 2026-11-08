package studioforyou.pl.backend.communication.notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;
import studioforyou.pl.backend.shared.api.ListResponse;

/**
 * The administrator's notification settings: which types the studio sends, when, and with what
 * content. Every new notification type appears here.
 */
@RestController
@RequestMapping("/api/v1/notification-types")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "communication")
class NotificationTypeController {

	private final NotificationStore store;

	NotificationTypeController(NotificationStore store) {
		this.store = store;
	}

	@GetMapping
	@Operation(summary = "List notification types and their settings", operationId = "listNotificationTypes")
	@ApiResponse(responseCode = "200", description = "Every notification type the application knows.")
	public ListResponse<NotificationTypeResponse> list() {
		return new ListResponse<>(Arrays.stream(NotificationType.values())
			.map(store::settings)
			.map(NotificationTypeResponse::from)
			.toList());
	}

	@GetMapping("/{code}")
	@Operation(summary = "Get a notification type's settings", operationId = "getNotificationType")
	@ApiResponse(responseCode = "200", description = "The type's settings.")
	@ApiResponse(responseCode = "404", description = "No such notification type.")
	public NotificationTypeResponse get(@PathVariable String code) {
		return NotificationTypeResponse.from(store.settings(type(code)));
	}

	@PatchMapping("/{code}")
	@Operation(summary = "Change a notification type's settings", operationId = "updateNotificationType",
			description = "Switches the type on or off, replaces its reminder times (timed types only) and its "
					+ "push title and text. Title and text may only use the type's placeholders. A change applies to "
					+ "notifications created from now on; ones already sent stay as they were.")
	@ApiResponse(responseCode = "200", description = "The type's new settings.")
	@ApiResponse(responseCode = "400", description = "Invalid settings, e.g. an unknown placeholder or duplicate reminder times.")
	@ApiResponse(responseCode = "404", description = "No such notification type.")
	public NotificationTypeResponse update(@PathVariable String code,
			@Valid @RequestBody UpdateNotificationTypeRequest request, Authentication authentication) {
		NotificationType type = type(code);
		NotificationStore.TypeSettings current = store.settings(type);
		if (request.leadTimesMinutes() != null && !type.timed()) {
			throw ApiException.invalidField("leadTimesMinutes", FieldErrorCode.INVALID_VALUE,
					"This notification type is sent right after an event, not before a start.");
		}
		List<Integer> leadTimes = current.leadTimes();
		if (request.leadTimesMinutes() != null) {
			if (new HashSet<>(request.leadTimesMinutes()).size() != request.leadTimesMinutes().size()) {
				throw ApiException.invalidField("leadTimesMinutes", FieldErrorCode.INVALID_VALUE,
						"Reminder times must be different.");
			}
			leadTimes = new ArrayList<>(request.leadTimesMinutes());
			leadTimes.sort(Comparator.reverseOrder());
		}
		String title = request.title() != null ? request.title().strip() : current.subject();
		String body = request.body() != null ? request.body().strip() : current.body();
		requireText("title", title, type);
		requireText("body", body, type);
		boolean enabled = request.enabled() != null ? request.enabled() : current.enabled();
		store.updateSettings(type, enabled, leadTimes, title, body, actor(authentication));
		return NotificationTypeResponse.from(store.settings(type));
	}

	private static void requireText(String field, String text, NotificationType type) {
		if (text.isBlank()) {
			throw ApiException.invalidField(field, FieldErrorCode.REQUIRED, "Must not be blank.");
		}
		Set<String> known = type.placeholders().stream().map(NotificationType.Placeholder::name)
			.collect(Collectors.toSet());
		List<String> unknown = NotificationTemplates.placeholders(text).stream().filter((name) -> !known.contains(name))
			.toList();
		if (!unknown.isEmpty()) {
			throw ApiException.invalidField(field, FieldErrorCode.INVALID_VALUE,
					"Unknown placeholders: " + unknown.stream().map((name) -> "{{" + name + "}}")
						.collect(Collectors.joining(", ")) + ".");
		}
	}

	private static NotificationType type(String code) {
		return NotificationType.fromCode(code)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Notification type not found."));
	}

	private static UUID actor(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.requireActiveAccountId();
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

}
