package studioforyou.pl.backend.communication.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import studioforyou.pl.backend.scheduling.ScheduleChangesRecorded.Kind;
import studioforyou.pl.backend.scheduling.ScheduleChangesRecorded.Notice;

/** What the notification about a change of a class says. */
class ScheduleNotificationsTest {

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	/** Tuesday 12.01.2027, 18:00 in Warsaw. */
	private static final Instant START = Instant.parse("2027-01-12T17:00:00Z");

	private static final Instant HOUR_LATER = START.plusSeconds(3600);

	@Test
	void describesANewTimeOnTheSameDay() {
		Notice notice = notice(Kind.CHANGED, START, HOUR_LATER, HOUR_LATER, HOUR_LATER.plusSeconds(3600),
				List.of("Sala 1"), List.of("Sala 1"), List.of("Anna Nowak"), List.of("Anna Nowak"));

		assertThat(ScheduleNotifications.changes(notice, WARSAW)).isEqualTo("godzina 18:00–19:00 → 19:00–20:00");
	}

	@Test
	void describesAnotherDayRoomAndLeadTogether() {
		Instant nextDay = START.plusSeconds(24 * 3600);
		Notice notice = notice(Kind.CHANGED, START, HOUR_LATER, nextDay, nextDay.plusSeconds(3600), List.of("Sala 1"),
				List.of(), List.of("Anna Nowak"), List.of("Ewa Lis", "Ola Kot"));

		assertThat(ScheduleNotifications.changes(notice, WARSAW)).isEqualTo("termin wtorek 12.01 18:00–19:00 → "
				+ "środa 13.01 18:00–19:00, sala Sala 1 → studio, prowadzący Anna Nowak → Ewa Lis, Ola Kot");
	}

	@Test
	void tellsATrainerAddedOrRemovedWhatItMeansForThem() {
		assertThat(ScheduleNotifications.changes(notice(Kind.ASSIGNED, START, HOUR_LATER, START, HOUR_LATER, List.of(),
				List.of(), List.of(), List.of()), WARSAW)).isEqualTo("dodano Cię do tych zajęć");
		assertThat(ScheduleNotifications.changes(notice(Kind.UNASSIGNED, START, HOUR_LATER, START, HOUR_LATER,
				List.of(), List.of(), List.of(), List.of()), WARSAW)).isEqualTo("usunięto Cię z tych zajęć");
		// Only the assisting staff changed.
		assertThat(ScheduleNotifications.changes(notice(Kind.CHANGED, START, HOUR_LATER, START, HOUR_LATER,
				List.of("Sala 1"), List.of("Sala 1"), List.of("Anna Nowak"), List.of("Anna Nowak")), WARSAW))
			.isEqualTo("zmiana obsady zajęć");
	}

	@Test
	void fillsTheClassAfterTheChange() {
		Notice notice = notice(Kind.CHANGED, START, HOUR_LATER, HOUR_LATER, HOUR_LATER.plusSeconds(3600),
				List.of("Sala 1"), List.of("Sala 2"), List.of("Anna Nowak"), List.of());

		assertThat(ScheduleNotifications.changeValues(notice, START.minusSeconds(3600), WARSAW))
			.containsEntry("zajęcia", "Pilates")
			.containsEntry("dzień", "dziś")
			.containsEntry("godzina", "19:00")
			.containsEntry("sala", "Sala 2")
			.containsEntry("prowadzący", "brak")
			.containsEntry("zmiany", "godzina 18:00–19:00 → 19:00–20:00, sala Sala 1 → Sala 2, prowadzący Anna Nowak → brak");
		assertThat(ScheduleNotifications.eventValues(null, START, List.of(), START.minusSeconds(24 * 3600), WARSAW))
			.containsEntry("zajęcia", "Zajęcia")
			.containsEntry("dzień", "jutro")
			.containsEntry("sala", "studio");
	}

	private static Notice notice(Kind kind, Instant previousStart, Instant previousEnd, Instant start, Instant end,
			List<String> previousRooms, List<String> rooms, List<String> previousLeads, List<String> leads) {
		return new Notice(UUID.randomUUID(), UUID.randomUUID(), kind, UUID.randomUUID(), null, "Pilates", previousStart,
				previousEnd, start, end, previousRooms, rooms, previousLeads, leads);
	}

}
