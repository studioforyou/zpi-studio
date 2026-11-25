package studioforyou.pl.backend.communication.history;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.jspecify.annotations.Nullable;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.communication.history.NotificationHistoryDtos.HistoryDetails;
import studioforyou.pl.backend.communication.history.NotificationHistoryDtos.HistoryItem;
import studioforyou.pl.backend.shared.api.PageResponse;

/** What the studio has sent to whom and whether it arrived, for the administrator. */
@RestController
@RequestMapping("/api/v1/notification-history")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "communication")
class NotificationHistoryController {

	private final NotificationHistoryService history;

	NotificationHistoryController(NotificationHistoryService history) {
		this.history = history;
	}

	@GetMapping
	@Operation(summary = "List sent notifications", operationId = "listNotificationHistory",
			description = "Every notification already sent (automatic ones and announcements), newest first, with "
					+ "its status. Filters combine. The text of chat message notifications is left out.")
	@ApiResponse(responseCode = "200", description = "One page of notifications.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: unknown type or a period that ends before "
			+ "it starts.")
	public PageResponse<HistoryItem> list(@RequestParam(required = false) @Nullable String type,
			@RequestParam(required = false) @Nullable UUID accountId,
			@RequestParam(required = false) @Nullable Instant from,
			@RequestParam(required = false) @Nullable Instant to,
			@RequestParam(required = false) @Nullable SendStatus status,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return history.page(type, accountId, from, to, status, page, size);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a sent notification with its delivery attempts", operationId = "getNotificationHistory")
	@ApiResponse(responseCode = "200", description = "The notification and every push attempt.")
	@ApiResponse(responseCode = "404", description = "No such notification, or not sent yet.")
	public HistoryDetails get(@PathVariable UUID id) {
		return history.details(id);
	}

}
