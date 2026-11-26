package studioforyou.pl.backend.integrations.calendar;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ApiProblem;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.ItemsResponse;

/**
 * The signed-in account's schedule as calendar entries, the same as in its private calendar feed.
 * The app writes them to a calendar on the phone when the user syncs right away, instead of waiting
 * for a subscribed calendar to download the feed again.
 */
@RestController
@PreAuthorize("hasAnyRole('CLIENT', 'TRAINER')")
@Tag(name = "integrations")
class OwnCalendarEventsController {

	static final String PATH = "/api/v1/me/calendar-events";

	private final CalendarFeedService feeds;

	OwnCalendarEventsController(CalendarFeedService feeds) {
		this.feeds = feeds;
	}

	@GetMapping(PATH)
	@Operation(summary = "List my calendar entries", operationId = "listOwnCalendarEvents",
			description = "The entries of the account's calendar feed, from 30 days ago on, ordered by start: "
					+ "reservations that were not cancelled, of the account's own profile and of the children it is a "
					+ "guardian of, events the studio cancelled (`cancelled`), and the events the account runs or "
					+ "assists as a trainer. It does not need a feed link.")
	@ApiResponse(responseCode = "200", description = "The entries.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED for an unknown role.",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	ItemsResponse<CalendarEntry> list(Authentication authentication,
			@Parameter(description = "Only this part of the schedule; both parts the account has without it.")
			@RequestParam(required = false) @Nullable CalendarFeedRole role) {
		return new ItemsResponse<>(this.feeds.entries(accountId(authentication), role, Instant.now())
			.stream()
			.map(CalendarEntry::of)
			.toList());
	}

	private static UUID accountId(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.requireActiveAccountId();
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

	/**
	 * One event of the account's schedule.
	 *
	 * @param uid stays the same for the event (and participant) in every response
	 * @param title already says whose event it is, e.g. "Zosia: Pole dance"
	 * @param location the rooms
	 * @param description the leads
	 * @param cancelled the studio cancelled the event
	 * @param sequence the event's version, which grows with every change
	 */
	@Schema(name = "CalendarEntry")
	record CalendarEntry(String uid, Instant startsAt, Instant endsAt, String title, @Nullable String location,
			@Nullable String description, boolean cancelled, int sequence) {

		static CalendarEntry of(ICalendarWriter.Entry entry) {
			return new CalendarEntry(entry.uid(), entry.startsAt(), entry.endsAt(), entry.summary(), entry.location(),
					entry.description(), entry.cancelled(), entry.sequence());
		}

	}

}
