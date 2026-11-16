package studioforyou.pl.backend.communication.timeoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** How a trainer's time off is described in a notification. */
class TimeOffNotificationsTests {

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	@Test
	void wholeDaysShowOnlyTheDates() {
		assertThat(TimeOffNotifications.period(Instant.parse("2026-11-18T23:00:00Z"),
				Instant.parse("2026-11-20T23:00:00Z"), WARSAW)).isEqualTo("19.11–20.11");
		assertThat(TimeOffNotifications.period(Instant.parse("2026-11-18T23:00:00Z"),
				Instant.parse("2026-11-19T23:00:00Z"), WARSAW)).isEqualTo("19.11");
	}

	@Test
	void partOfADayShowsTheHours() {
		assertThat(TimeOffNotifications.period(Instant.parse("2026-11-17T16:00:00Z"),
				Instant.parse("2026-11-17T18:00:00Z"), WARSAW)).isEqualTo("17.11, 17:00–19:00");
		assertThat(TimeOffNotifications.period(Instant.parse("2026-11-17T16:00:00Z"),
				Instant.parse("2026-11-18T08:00:00Z"), WARSAW)).isEqualTo("17.11 17:00 – 18.11 9:00");
	}

	@Test
	void kindsHavePolishNames() {
		assertThat(TimeOffNotifications.kindLabel("VACATION")).isEqualTo("Urlop");
		assertThat(TimeOffNotifications.kindLabel("SICK_LEAVE")).isEqualTo("Zwolnienie lekarskie");
		assertThat(TimeOffNotifications.kindLabel("UNAVAILABLE")).isEqualTo("Inna niedostępność");
	}

}
