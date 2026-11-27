package studioforyou.pl.backend.integrations.calendar;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * The iCalendar feed that external calendars download. It is a token operation: the secret token
 * in the link is the proof, so the request carries no {@code Authorization} header.
 */
@RestController
@Tag(name = "integrations")
class CalendarFeedController {

	static final MediaType TEXT_CALENDAR = new MediaType("text", "calendar", java.nio.charset.StandardCharsets.UTF_8);

	private final CalendarFeedService feeds;

	CalendarFeedController(CalendarFeedService feeds) {
		this.feeds = feeds;
	}

	@GetMapping(CalendarFeedService.FEED_PATH + "{token}" + CalendarFeedService.FEED_FILE)
	@SecurityRequirements
	@Operation(summary = "Download a calendar feed", operationId = "getCalendarFeed",
			description = "An iCalendar document (RFC 5545) with the schedule of the link's account, from 30 days ago "
					+ "on: reservations that were not cancelled, events the studio cancelled (STATUS:CANCELLED) and, for a "
					+ "trainer, the events they run or assist. Times are in UTC.")
	@ApiResponse(responseCode = "200", description = "The calendar.",
			content = @Content(mediaType = "text/calendar", schema = @Schema(type = "string")))
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED for an unknown role.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "The link is unknown or was turned off, or its account is no "
			+ "longer active.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ResponseEntity<String> feed(@PathVariable String token,
			@Parameter(description = "Only this part of the schedule; both parts the account has without it.")
			@RequestParam(required = false) @Nullable CalendarFeedRole role) {
		return ResponseEntity.ok()
			.contentType(TEXT_CALENDAR)
			.cacheControl(CacheControl.noCache().cachePrivate())
			.body(this.feeds.render(token, role, Instant.now()));
	}

}
