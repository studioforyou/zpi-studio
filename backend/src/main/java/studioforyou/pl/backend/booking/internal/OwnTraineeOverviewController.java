package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ListResponse;

/** Read-only package and personal-session view for currently assigned participants. */
@RestController
@PreAuthorize("hasRole('TRAINER')")
@RequestMapping("/api/v1/me/trainees")
@Tag(name = "booking")
public class OwnTraineeOverviewController {

	private final TraineeOverviewQueries queries;

	public OwnTraineeOverviewController(TraineeOverviewQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/overview")
	@Operation(summary = "Read my trainees' package and session summaries", operationId = "listOwnTraineeOverviews")
	public TraineeOverviewQueries.OverviewList list(AccountAuthentication authentication) {
		return queries.list(authentication.requireActiveAccountId());
	}

	@GetMapping("/personal-trainings")
	@Operation(summary = "Read my trainees' personal sessions in a shared calendar", operationId = "listOwnTraineesPersonalTrainings",
			description = "Personal sessions of currently assigned active participants with this trainer, overlapping [from, to), "
					+ "at most 93 days. Each item represents one participant's reservation, including existing accounting facts.")
	public TraineeOverviewQueries.CalendarList sharedCalendar(@RequestParam Instant from, @RequestParam Instant to,
			AccountAuthentication authentication) {
		return queries.calendar(authentication.requireActiveAccountId(), from, to);
	}

	@GetMapping("/{customerId}/overview")
	@Operation(summary = "Read my trainee's packages and session summary", operationId = "getOwnTraineeOverview")
	public TraineeOverviewQueries.OverviewDetail detail(@PathVariable UUID customerId, AccountAuthentication authentication) {
		return queries.detail(authentication.requireActiveAccountId(), customerId);
	}

	@GetMapping("/{customerId}/personal-trainings")
	@Operation(summary = "Read my trainee's personal sessions in a calendar window", operationId = "listOwnTraineePersonalTrainings",
			description = "Sessions with this trainer overlapping [from, to), at most 93 days. Shows existing confirmations "
					+ "and accounting facts without confirming, cancelling, reserving or spending entries.")
	public ListResponse<TraineeOverviewQueries.Session> calendar(@PathVariable UUID customerId, @RequestParam Instant from,
			@RequestParam Instant to, AccountAuthentication authentication) {
		return new ListResponse<>(queries.calendar(authentication.requireActiveAccountId(), customerId, from, to));
	}

}
