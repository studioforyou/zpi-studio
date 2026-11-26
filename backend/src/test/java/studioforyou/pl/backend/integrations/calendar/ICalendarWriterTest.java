package studioforyou.pl.backend.integrations.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import studioforyou.pl.backend.integrations.calendar.ICalendarWriter.Entry;

class ICalendarWriterTest {

	private static final Instant START = Instant.parse("2026-12-01T17:30:00Z");

	@Test
	void writesEventsInUtcWithCrlfLineEnds() {
		String calendar = ICalendarWriter.write("Mój grafik",
				List.of(new Entry("e1@studioforyou.pl", START, START.plusSeconds(3600), "Pole dance", "Sala 1",
						"Prowadzi: Anna Kowalska", false, 3)),
				Instant.parse("2026-11-26T12:00:00Z"));

		assertThat(calendar).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VCALENDAR\r\n");
		assertThat(calendar.replace("\r\n", "")).doesNotContain("\n");
		assertThat(calendar).contains("X-WR-CALNAME:Mój grafik\r\n", "UID:e1@studioforyou.pl\r\n",
				"DTSTAMP:20261126T120000Z\r\n", "DTSTART:20261201T173000Z\r\n", "DTEND:20261201T183000Z\r\n",
				"SEQUENCE:3\r\n", "SUMMARY:Pole dance\r\n", "LOCATION:Sala 1\r\n",
				"DESCRIPTION:Prowadzi: Anna Kowalska\r\n", "STATUS:CONFIRMED\r\n");
	}

	@Test
	void marksCancelledEventsAndLeavesOutEmptyOptionalProperties() {
		String calendar = ICalendarWriter.write("Grafik",
				List.of(new Entry("e2", START, START.plusSeconds(60), "Joga", null, " ", true, 0)), START);

		assertThat(calendar).contains("STATUS:CANCELLED\r\n").doesNotContain("LOCATION", "DESCRIPTION");
	}

	@Test
	void escapesTextValues() {
		assertThat(ICalendarWriter.text("a\\b;c,d\ne\r\nf\rg")).isEqualTo("a\\\\b\\;c\\,d\\ne\\nf\\ng");
	}

	@Test
	void foldsLinesLongerThan75OctetsWithoutSplittingCharacters() {
		String summary = "Zajęcia ".repeat(30);
		String calendar = ICalendarWriter.write("Grafik",
				List.of(new Entry("e3", START, START.plusSeconds(60), summary, null, null, false, 0)), START);

		assertThat(Arrays.stream(calendar.split("\r\n")))
			.allSatisfy((line) -> assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75));
		assertThat(calendar.replace("\r\n ", "")).contains("SUMMARY:" + summary + "\r\n");
	}

}
