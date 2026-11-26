package studioforyou.pl.backend.integrations.calendar;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * The signed-in account's private calendar feed link, which Google Calendar, Apple Calendar and
 * other calendars subscribe to.
 */
@RestController
@PreAuthorize("hasAnyRole('CLIENT', 'TRAINER')")
@RequestMapping(OwnCalendarFeedController.PATH)
@Tag(name = "integrations")
class OwnCalendarFeedController {

	static final String PATH = "/api/v1/me/calendar-feed";

	private final CalendarFeedService feeds;

	OwnCalendarFeedController(CalendarFeedService feeds) {
		this.feeds = feeds;
	}

	@GetMapping
	@Operation(summary = "Check my calendar feed link", operationId = "getOwnCalendarFeed",
			description = "Whether the account has a working link and when it was created. The link itself is "
					+ "returned only when it is created, because the backend keeps only a hash of its token.")
	@ApiResponse(responseCode = "200", description = "The link's state.")
	CalendarFeedStatus get(Authentication authentication) {
		return this.feeds.findActive(accountId(authentication))
			.map((createdAt) -> new CalendarFeedStatus(true, createdAt))
			.orElseGet(() -> new CalendarFeedStatus(false, null));
	}

	@PostMapping
	@Operation(summary = "Create a new calendar feed link", operationId = "createOwnCalendarFeed",
			description = "Creates a secret link to an iCalendar feed of the account's schedule: its reservations "
					+ "and those of the children it is a guardian of (client), and the events it runs or assists "
					+ "(trainer). Adding `?role=CLIENT` or `?role=TRAINER` to the link limits it to that part. "
					+ "The previous link stops working.")
	@ApiResponse(responseCode = "201", description = "The new link. It is not shown again.")
	ResponseEntity<CalendarFeedLink> create(Authentication authentication) {
		CalendarFeedService.IssuedFeed feed = this.feeds.issue(accountId(authentication));
		return ResponseEntity.created(URI.create(PATH))
			.body(new CalendarFeedLink(feed.url(), feed.webcalUrl(), feed.createdAt()));
	}

	@DeleteMapping
	@Operation(summary = "Turn off my calendar feed", operationId = "deleteOwnCalendarFeed",
			description = "The link stops working; subscribed calendars keep the events they already have but get no "
					+ "updates. Idempotent.")
	@ApiResponse(responseCode = "204", description = "The link no longer works.")
	ResponseEntity<Void> delete(Authentication authentication) {
		this.feeds.revoke(accountId(authentication));
		return ResponseEntity.noContent().build();
	}

	private static UUID accountId(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.requireActiveAccountId();
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

	/**
	 * @param active whether the account has a working link
	 * @param createdAt when the working link was created, or {@code null} without one
	 */
	@Schema(name = "CalendarFeedStatus")
	record CalendarFeedStatus(boolean active, @Nullable Instant createdAt) {
	}

	/**
	 * @param url the feed's https address, e.g. for Google Calendar's "From URL"
	 * @param webcalUrl the same address with the {@code webcal} scheme, which opens the subscription
	 * dialog of Apple Calendar
	 */
	@Schema(name = "CalendarFeedLink")
	record CalendarFeedLink(
			@Schema(example = "https://api.example.com/api/v1/calendar-feeds/Xy3.../events.ics") String url,
			@Schema(example = "webcal://api.example.com/api/v1/calendar-feeds/Xy3.../events.ics") String webcalUrl,
			Instant createdAt) {
	}

}
