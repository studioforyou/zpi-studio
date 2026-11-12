package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import studioforyou.pl.backend.NotificationCleanup;

/**
 * Push notifications about changes of a class ({@code CLASS_CHANGED}, #144): every change notice
 * the schedule leaves becomes a notification for the participant or the trainer it concerns. They
 * are created in the background after the change commits.
 */
class ScheduleChangeNotificationTest extends ScheduleTestSupport {

	private static final String CUSTOMER_NAME = "Change-notification";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d.MM",
			Locale.forLanguageTag("pl-PL"));

	@AfterEach
	void removeCustomers() {
		NotificationCleanup.deleteNotifications(this.jdbcClient,
				"SELECT id FROM account WHERE email LIKE '%" + TEST_EMAIL_DOMAIN + "'");
		this.jdbcTemplate.update("DELETE FROM reservation WHERE customer_id IN "
				+ "(SELECT id FROM customer_profile WHERE first_name = ?)", CUSTOMER_NAME);
		this.jdbcTemplate.update("DELETE FROM customer_profile WHERE first_name = ?", CUSTOMER_NAME);
	}

	@Test
	void aMovedClassNotifiesItsParticipantAndTrainer() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		UUID client = clientAccount("ola");
		book(id, client);

		put("/api/v1/schedule-events/" + id, """
				{"version": 0, "startsAt": "%s", "endsAt": "%s", "title": null, "capacity": null,
				 "staff": [%s], "resources": [%s]}
				""".formatted(at(this.monday, "19:00"), at(this.monday, "20:00"), staff(ANNA, "LEAD"),
				room(GYM, true)))
			.andExpect(status().isOk());

		UUID anna = account("anna");
		await(() -> notifications(client).size() == 1 && notifications(anna).size() == 1);
		String expected = "Zmiana zajęć / Pole dance, " + DATE.format(this.monday) + " 19:00: godzina 18:00–19:00 → "
				+ "19:00–20:00, sala Pole room → Gym.";
		assertThat(notifications(client)).containsExactly(expected);
		assertThat(notifications(anna)).containsExactly(expected);
		assertThat(this.jdbcClient.sql("SELECT payload ->> 'eventId' FROM notification WHERE recipient_id = :id")
			.param("id", client)
			.query(String.class)
			.single()).isEqualTo(id);
	}

	@Test
	void anotherLeadTrainerTellsBothTrainersWhatItMeansForThem() throws Exception {
		String id = createEvent(poleClass(this.monday, "18:00", "19:00"));
		UUID client = clientAccount("ewa");
		book(id, client);

		put("/api/v1/schedule-events/" + id + "/assignments", """
				{"version": 0, "staff": [%s], "resources": [%s]}""".formatted(staff(MAREK, "LEAD"),
				room(POLE_ROOM, true)))
			.andExpect(status().isOk());

		UUID anna = account("anna");
		UUID marek = account("marek");
		await(() -> notifications(client).size() == 1 && notifications(anna).size() == 1
				&& notifications(marek).size() == 1);
		String day = "Pole dance, " + DATE.format(this.monday) + " 18:00: ";
		assertThat(notifications(client))
			.containsExactly("Zmiana zajęć / " + day + "prowadzący Anna Kowalska → Marek Nowak.");
		assertThat(notifications(anna)).containsExactly("Zmiana zajęć / " + day + "usunięto Cię z tych zajęć.");
		assertThat(notifications(marek)).containsExactly("Zmiana zajęć / " + day + "dodano Cię do tych zajęć.");
	}

	private UUID clientAccount(String name) {
		UUID account = this.jdbcTemplate.queryForObject(
				"INSERT INTO account (email, status) VALUES (?, 'ACTIVE') RETURNING id", UUID.class,
				name + TEST_EMAIL_DOMAIN);
		this.jdbcTemplate.update("INSERT INTO account_role (account_id, role_id) SELECT ?, id FROM role WHERE code = ?",
				account, "CLIENT");
		this.jdbcTemplate.update("INSERT INTO customer_profile (account_id, first_name, last_name) VALUES (?, ?, ?)",
				account, CUSTOMER_NAME, name);
		return account;
	}

	private void book(String eventId, UUID clientAccount) {
		UUID customer = this.jdbcTemplate.queryForObject("SELECT id FROM customer_profile WHERE account_id = ?",
				UUID.class, clientAccount);
		this.jdbcTemplate.update("""
				INSERT INTO reservation (event_id, customer_id, booked_by, free_cancel_until)
				VALUES (?, ?, ?, ?)
				""", UUID.fromString(eventId), customer, clientAccount, Timestamp.from(at(this.monday, "06:00")));
	}

	private UUID account(String name) {
		return this.jdbcTemplate.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class,
				name + TEST_EMAIL_DOMAIN);
	}

	private List<String> notifications(UUID account) {
		return this.jdbcClient.sql("""
				SELECT n.title || ' / ' || n.body FROM notification n
				JOIN notification_type t ON t.id = n.notification_type_id
				WHERE n.recipient_id = :id AND t.code = 'CLASS_CHANGED' ORDER BY n.created_at, n.id
				""").param("id", account).query(String.class).list();
	}

	private static void await(BooleanSupplier condition) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (!condition.getAsBoolean()) {
			if (Instant.now().isAfter(deadline)) {
				throw new AssertionError("Condition not met within 10 seconds");
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ex);
			}
		}
	}

}
