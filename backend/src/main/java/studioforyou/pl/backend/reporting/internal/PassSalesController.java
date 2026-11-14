package studioforyou.pl.backend.reporting.internal;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What clients buy most, for administrators (#167).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/reports")
@Tag(name = "reporting")
class PassSalesController {

	private final PassSalesReport report;

	PassSalesController(PassSalesReport report) {
		this.report = report;
	}

	@GetMapping("/pass-sales")
	@Operation(summary = "Report passes sold by kind, product and price variant", operationId = "getPassSales",
			description = "Passes that became a participant's in the period: bought and paid for by a client "
					+ "(online) or assigned by the studio staff (studio), on the studio date of the payment or "
					+ "assignment. Passes waiting for the payment and cancelled passes are left out. Revenue is "
					+ "the price actually paid, in minor units of the studio's currency; a pass given for free "
					+ "counts as sold for 0 and in free. Totals are also given for the period of the same length "
					+ "right before.")
	@ApiResponse(responseCode = "400",
			description = "VALIDATION_FAILED when to is before from or the period is longer than 731 days.")
	PassSalesReport.Report passSales(
			@Parameter(description = "First day of the period, in the studio's time zone.", example = "2026-10-01")
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@Parameter(description = "Last day of the period (inclusive).", example = "2026-10-31")
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
		return this.report.build(ReportPeriod.of(from, to));
	}

}
