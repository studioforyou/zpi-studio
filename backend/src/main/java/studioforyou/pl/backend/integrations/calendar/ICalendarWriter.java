package studioforyou.pl.backend.integrations.calendar;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Writes an iCalendar document (RFC 5545) that external calendars subscribe to. Times are written
 * in UTC, so the document needs no time zone definitions; each calendar shows them in its own zone.
 */
final class ICalendarWriter {

	private static final String CRLF = "\r\n";

	// RFC 5545 3.1: lines longer than 75 octets are folded.
	private static final int MAX_LINE_OCTETS = 75;

	private static final DateTimeFormatter UTC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private ICalendarWriter() {
	}

	/**
	 * @param name the calendar's name, shown by calendars that support {@code X-WR-CALNAME}
	 * @param generatedAt the time written as every event's {@code DTSTAMP}
	 */
	static String write(String name, List<Entry> entries, Instant generatedAt) {
		StringBuilder out = new StringBuilder();
		line(out, "BEGIN:VCALENDAR");
		line(out, "VERSION:2.0");
		line(out, "PRODID:-//studioforyou//ZPI Studio//PL");
		line(out, "CALSCALE:GREGORIAN");
		line(out, "METHOD:PUBLISH");
		line(out, "X-WR-CALNAME:" + text(name));
		// Hints for calendars that let the feed suggest how often to download it again.
		line(out, "REFRESH-INTERVAL;VALUE=DURATION:PT1H");
		line(out, "X-PUBLISHED-TTL:PT1H");
		String stamp = UTC_DATE_TIME.format(generatedAt);
		for (Entry entry : entries) {
			line(out, "BEGIN:VEVENT");
			line(out, "UID:" + text(entry.uid()));
			line(out, "DTSTAMP:" + stamp);
			line(out, "DTSTART:" + UTC_DATE_TIME.format(entry.startsAt()));
			line(out, "DTEND:" + UTC_DATE_TIME.format(entry.endsAt()));
			line(out, "SEQUENCE:" + entry.sequence());
			line(out, "SUMMARY:" + text(entry.summary()));
			if (entry.location() != null && !entry.location().isBlank()) {
				line(out, "LOCATION:" + text(entry.location()));
			}
			if (entry.description() != null && !entry.description().isBlank()) {
				line(out, "DESCRIPTION:" + text(entry.description()));
			}
			line(out, "STATUS:" + (entry.cancelled() ? "CANCELLED" : "CONFIRMED"));
			line(out, "TRANSP:OPAQUE");
			line(out, "END:VEVENT");
		}
		line(out, "END:VCALENDAR");
		return out.toString();
	}

	/** Escapes a TEXT value (RFC 5545 3.3.11). */
	static String text(String value) {
		StringBuilder escaped = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '\\' -> escaped.append("\\\\");
				case ';' -> escaped.append("\\;");
				case ',' -> escaped.append("\\,");
				case '\n' -> escaped.append("\\n");
				case '\r' -> {
					// A CRLF becomes a single escaped line break.
					if (i + 1 >= value.length() || value.charAt(i + 1) != '\n') {
						escaped.append("\\n");
					}
				}
				default -> escaped.append(c);
			}
		}
		return escaped.toString();
	}

	/** Appends a content line, folded so that no physical line exceeds 75 octets of UTF-8. */
	private static void line(StringBuilder out, String content) {
		int octets = 0;
		int limit = MAX_LINE_OCTETS;
		for (int i = 0; i < content.length();) {
			int codePoint = content.codePointAt(i);
			int length = Character.charCount(codePoint);
			int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
			if (octets + size > limit) {
				out.append(CRLF).append(' ');
				octets = 0;
				// The leading space of a continuation line counts towards its 75 octets.
				limit = MAX_LINE_OCTETS - 1;
			}
			out.appendCodePoint(codePoint);
			octets += size;
			i += length;
		}
		out.append(CRLF);
	}

	/**
	 * One event of the calendar.
	 *
	 * @param uid stays the same for the event in every download, so calendars update it in place
	 * @param sequence increases when the event changes
	 */
	record Entry(String uid, Instant startsAt, Instant endsAt, String summary, @Nullable String location,
			@Nullable String description, boolean cancelled, int sequence) {
	}

}
