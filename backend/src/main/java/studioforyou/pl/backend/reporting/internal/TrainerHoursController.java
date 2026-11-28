package studioforyou.pl.backend.reporting.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator's worked-hours report (#169). */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/reports")
@Tag(name = "reporting")
class TrainerHoursController {

	private final TrainerHoursReport report;

	TrainerHoursController(TrainerHoursReport report) {
		this.report = report;
	}

	@GetMapping("/trainer-hours")
	@Operation(summary = "Report instructors' worked hours", operationId = "getTrainerHours",
			description = "Counts full elapsed time of non-cancelled events that ended by now and started within "
					+ "the inclusive period of studio dates. Covers classes, workshops, personal training and "
					+ "consultations. Each assigned instructor gets the full time, separately for leading and "
					+ "assisting; confirmed means the event is COMPLETED. Pending substitutions do not transfer time. "
					+ "Active and inactive instructors, including zero-hour rows, are returned. No payroll or attendance is changed.")
	TrainerHoursReport.Report hours(@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
			@RequestParam(required = false) @Nullable List<TrainerHoursReport.Kind> kind,
			@RequestParam(required = false) @Nullable UUID staffId,
			@RequestParam(defaultValue = "false") boolean confirmedOnly) {
		return this.report.build(ReportPeriod.of(from, to), kind == null || kind.isEmpty()
				? EnumSet.allOf(TrainerHoursReport.Kind.class) : EnumSet.copyOf(kind), staffId, confirmedOnly, Instant.now());
	}
}
