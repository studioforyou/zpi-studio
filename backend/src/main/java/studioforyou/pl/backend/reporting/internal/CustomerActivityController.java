package studioforyou.pl.backend.reporting.internal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.reporting.internal.CustomerActivityReport.Comparison;
import studioforyou.pl.backend.reporting.internal.CustomerActivityReport.CustomerActivity;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * How many customers use the studio and how many join it, for administrators.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/reports")
@Tag(name = "reporting")
class CustomerActivityController {

	/** Two years, so a whole year can be compared with the one before. */
	static final long MAX_DAYS = 731;

	private final CustomerActivityReport report;

	CustomerActivityController(CustomerActivityReport report) {
		this.report = report;
	}

	@GetMapping("/customer-activity")
	@Operation(summary = "Count active and new customers", operationId = "getCustomerActivity",
			description = "Active customers: participants with a pass valid on at least one day of the period "
					+ "or a reservation that was not cancelled for an event starting in it. New customers: client "
					+ "accounts activated in the period and participant profiles without an account added in it. "
					+ "The same counts are returned for the period of the same length right before.")
	@ApiResponse(responseCode = "400",
			description = "VALIDATION_FAILED when to is before from or the period is longer than 731 days.")
	CustomerActivityResponse customerActivity(
			@Parameter(description = "First day of the period, in the studio's time zone.")
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@Parameter(description = "Last day of the period (inclusive).")
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		if (to.isBefore(from)) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE, "to must not be before from.");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE,
					"The period must not be longer than " + MAX_DAYS + " days.");
		}
		return CustomerActivityResponse.from(report.compare(from, to));
	}

	@Schema(name = "CustomerActivityReport")
	record CustomerActivityResponse(PeriodCounts current,
			@Schema(description = "The period of the same length right before the requested one.") PeriodCounts previous) {

		static CustomerActivityResponse from(Comparison comparison) {
			return new CustomerActivityResponse(PeriodCounts.from(comparison.current()),
					PeriodCounts.from(comparison.previous()));
		}

	}

	@Schema(name = "CustomerActivity", description = "Customer counts in a period of studio dates.")
	record PeriodCounts(@Schema(example = "2026-10-01") LocalDate from, @Schema(example = "2026-10-31") LocalDate to,
			@Schema(example = "42") long activeCustomers, @Schema(example = "7") long newCustomers) {

		static PeriodCounts from(CustomerActivity activity) {
			return new PeriodCounts(activity.from(), activity.to(), activity.activeCustomers(),
					activity.newCustomers());
		}

	}

}
