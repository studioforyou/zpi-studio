package studioforyou.pl.backend.reporting.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/**
 * A period of studio dates a report covers, both ends included.
 */
record ReportPeriod(LocalDate from, LocalDate to) {

	/** Two years, so a whole year can be compared with the one before. */
	static final long MAX_DAYS = 731;

	/** The period an administrator asked for, rejected when it is reversed or too long. */
	static ReportPeriod of(LocalDate from, LocalDate to) {
		if (to.isBefore(from)) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE, "to must not be before from.");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
			throw ApiException.invalidField("to", FieldErrorCode.OUT_OF_RANGE,
					"The period must not be longer than " + MAX_DAYS + " days.");
		}
		return new ReportPeriod(from, to);
	}

	/** Midnight starting the first day in the studio's time zone. */
	Instant start(ZoneId zone) {
		return this.from.atStartOfDay(zone).toInstant();
	}

	/** Midnight ending the last day in the studio's time zone, exclusive. */
	Instant end(ZoneId zone) {
		return this.to.plusDays(1).atStartOfDay(zone).toInstant();
	}

}
