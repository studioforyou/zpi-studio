package studioforyou.pl.backend.communication.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Room;

class ClassRemindersTest {

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	private static final Instant START = Instant.parse("2026-11-10T17:00:00Z"); // Tuesday 18:00 in Warsaw

	private static final Instant BOOKED_EARLY = START.minusSeconds(7 * 24 * 3600);

	@Test
	void isDueFromTheMomentForTheGracePeriod() {
		Instant moment = START.minusSeconds(120 * 60);
		assertThat(ClassReminders.isDue(START, 120, BOOKED_EARLY, moment.minusSeconds(1))).isFalse();
		assertThat(ClassReminders.isDue(START, 120, BOOKED_EARLY, moment)).isTrue();
		assertThat(ClassReminders.isDue(START, 120, BOOKED_EARLY, moment.plus(ClassReminders.GRACE).minusSeconds(1)))
			.isTrue();
		assertThat(ClassReminders.isDue(START, 120, BOOKED_EARLY, moment.plus(ClassReminders.GRACE))).isFalse();
	}

	@Test
	void aReservationMadeAfterTheMomentGetsNoReminderForThatLeadTime() {
		Instant moment = START.minusSeconds(24 * 3600);
		assertThat(ClassReminders.isDue(START, 1440, moment.plusSeconds(60), moment.plusSeconds(120))).isFalse();
		assertThat(ClassReminders.isDue(START, 1440, moment, moment.plusSeconds(120))).isTrue();
	}

	@Test
	void neverAfterTheStart() {
		assertThat(ClassReminders.isDue(START, 10, BOOKED_EARLY, START)).isFalse();
	}

	@Test
	void describesTheLeadTime() {
		assertThat(ClassReminders.leadTime(120)).isEqualTo("2 godz.");
		assertThat(ClassReminders.leadTime(90)).isEqualTo("1 godz. 30 min");
		assertThat(ClassReminders.leadTime(45)).isEqualTo("45 min");
		assertThat(ClassReminders.leadTime(1440)).isEqualTo("1 dzień");
		assertThat(ClassReminders.leadTime(2880)).isEqualTo("2 dni");
	}

	@Test
	void fillsTheValuesInTheStudioTimeZone() {
		Occurrence event = event("Pilates", List.of(new Room(UUID.randomUUID(), "Sala 1"),
				new Room(UUID.randomUUID(), "Sala 2")));
		var sameDay = ClassReminders.values(event, 120, START.minusSeconds(7200), WARSAW);
		assertThat(sameDay).containsEntry("zajęcia", "Pilates").containsEntry("dzień", "dziś")
			.containsEntry("godzina", "18:00").containsEntry("sala", "Sala 1, Sala 2").containsEntry("ile przed", "2 godz.");
		assertThat(ClassReminders.values(event, 1440, START.minusSeconds(24 * 3600), WARSAW)).containsEntry("dzień", "jutro");
		assertThat(ClassReminders.values(event, 2880, START.minusSeconds(48 * 3600), WARSAW))
			.containsEntry("dzień", "wtorek 10.11");
	}

	@Test
	void fallsBackToTheServiceNameAndTheStudio() {
		Occurrence event = event(null, List.of());
		assertThat(ClassReminders.values(event, 120, START.minusSeconds(7200), WARSAW))
			.containsEntry("zajęcia", "Trening personalny").containsEntry("sala", "studio");
	}

	private static Occurrence event(String title, List<Room> rooms) {
		return new Occurrence(UUID.randomUUID(), title, UUID.randomUUID(), "Trening personalny", null, null, START,
				START.plusSeconds(3600), "SCHEDULED", 10, List.of(), rooms, 0);
	}

}
