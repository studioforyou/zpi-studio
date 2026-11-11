package studioforyou.pl.backend.booking.internal;

import java.util.UUID;
import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.PageResponse;

/** Entry history for the active account's own participant or a currently represented child. */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "membership")
class OwnPassUsageController {

	private final PassUsageQueries queries;

	OwnPassUsageController(PassUsageQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/profiles/{profileId}/passes/{passId}/entry-history")
	@Operation(summary = "Read my pass entry history", operationId = "listOwnPassEntryHistory",
			description = "One entry per reservation, plus individual balance corrections, latest accounting change first. "
					+ "Includes expired passes, private sessions and unlimited passes. Does not change balances or attendance. "
					+ "Unknown or unauthorized participant/pass pairs return 404; internal notes and actors are not exposed.")
	PageResponse<PassUsageQueries.UsageItem> list(@PathVariable UUID profileId, @PathVariable UUID passId,
			@RequestParam(defaultValue = "false") boolean includeUpcoming,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, AccountAuthentication authentication) {
		return queries.list(authentication.requireActiveAccountId(), profileId, passId, includeUpcoming, page, size);
	}

	@GetMapping("/pass-entry-history")
	@Operation(summary = "Read entry history of my account", operationId = "listAccountPassEntryHistory",
			description = "Own profile and currently represented children, including expired passes. "
					+ "Ordered by the latest accounting change, newest first, with the class date separately. "
					+ "Only ended events and corrections are shown by default; includeUpcoming also shows future and ongoing bookings. "
					+ "Filters and totals are applied before paging. Date range is from-inclusive, to-exclusive.")
	PassUsageQueries.AccountHistory accountHistory(@RequestParam(required = false) UUID profileId,
			@RequestParam(required = false) UUID passId, @RequestParam(required = false) UUID productId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "false") boolean includeUpcoming,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, AccountAuthentication authentication) {
		return queries.accountHistory(authentication.requireActiveAccountId(), profileId, passId, productId, from, to, includeUpcoming, page, size);
	}

}
