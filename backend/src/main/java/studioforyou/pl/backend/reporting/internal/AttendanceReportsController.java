package studioforyou.pl.backend.reporting.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How well classes fill and how many places are lost to no-shows and late cancellations, for
 * administrators (#164, #166). Both count only events that already started and were not cancelled,
 * and take the same filters of the events they cover.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/v1/reports")
@Tag(name = "reporting")
class AttendanceReportsController {

	private final ClassAttendanceReport classAttendance;

	private final BookingOutcomesReport bookingOutcomes;

	AttendanceReportsController(ClassAttendanceReport classAttendance, BookingOutcomesReport bookingOutcomes) {
		this.classAttendance = classAttendance;
		this.bookingOutcomes = bookingOutcomes;
	}

	@GetMapping("/class-attendance")
	@Operation(summary = "Report attendance and occupancy of group classes", operationId = "getClassAttendance",
			description = "Group classes and workshops starting in the period that already started, were not "
					+ "cancelled and match the filters: in total, per class (a recurring series, or a one-off event) "
					+ "and per studio date. A participant attended unless marked absent: an unmarked place counts as "
					+ "attended, as when the trainer completes the class, and is also counted in unmarked. Occupancy "
					+ "is placesBooked / places, over the classes with a limit of places. kind defaults to CLASS and "
					+ "WORKSHOP; personal training has no occupancy, so it cannot be asked for.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when to is before from, the period is "
			+ "longer than 731 days, a kind other than CLASS or WORKSHOP is asked for or an ID is malformed.")
	ClassAttendanceReport.Report classAttendance(@Valid ReportQuery query) {
		return this.classAttendance.build(query.period(),
				query.filter(ReportEventKind.GROUP, ReportEventKind.GROUP));
	}

	@GetMapping("/booking-outcomes")
	@Operation(summary = "Report no-shows and cancellations", operationId = "getBookingOutcomes",
			description = "What became of the places booked for events starting in the period that already started, "
					+ "were not cancelled and match the filters: attended, absent (no-shows), cancelled in time or "
					+ "late, in total, per service type with its current cancellation rules and per studio date, "
					+ "and how long before the start the places were cancelled. An unmarked place counts as "
					+ "attended and is also counted in unmarked. kind defaults to CLASS and WORKSHOP, so personal "
					+ "training is reported only when asked for.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED when to is before from, the period is "
			+ "longer than 731 days or an ID or kind is malformed.")
	BookingOutcomesReport.Report bookingOutcomes(@Valid ReportQuery query) {
		return this.bookingOutcomes.build(query.period(), query.filter(ReportEventKind.ALL, ReportEventKind.GROUP));
	}

}
