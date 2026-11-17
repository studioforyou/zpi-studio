package studioforyou.pl.backend.scheduling.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A trainer moves a personal training they run to the new time or room agreed with the client
 * (#125): only the time and rooms change, the usual planning checks apply and the client's free
 * cancellation deadline moves with the training.
 */
class OwnPersonalTrainingChangeTest extends ScheduleTestSupport {

	private static final String EVENTS = "/api/v1/me/schedule-events/";

	private static final String CUSTOMER_NAME = "Personal-change";

	@AfterEach
	void removeCustomers() {
		this.jdbcTemplate.update("DELETE FROM reservation WHERE customer_id IN "
				+ "(SELECT id FROM customer_profile WHERE first_name = ?)", CUSTOMER_NAME);
		this.jdbcTemplate.update("DELETE FROM customer_profile WHERE first_name = ?", CUSTOMER_NAME);
	}

	@Test
	void trainerMovesTheirPersonalTrainingAndKeepsEverythingElse() throws Exception {
		String id = personalTraining(MAREK, "10:00", "11:00");

		putAs(MAREK_TOKEN, id, move(0, "16:00", "17:00", GYM)).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.kind").value("PERSONAL_TRAINING"))
			.andExpect(jsonPath("$.visibility").value("PRIVATE"))
			.andExpect(jsonPath("$.startsAt").value(at(this.monday, "16:00").toString()))
			.andExpect(jsonPath("$.endsAt").value(at(this.monday, "17:00").toString()))
			.andExpect(jsonPath("$.title").value("Personal training"))
			.andExpect(jsonPath("$.capacity").value(1))
			.andExpect(jsonPath("$.staff.length()").value(1))
			.andExpect(jsonPath("$.staff[0].staffId").value(MAREK.toString()))
			.andExpect(jsonPath("$.staff[0].role").value("LEAD"))
			.andExpect(jsonPath("$.resources[0].resourceId").value(GYM.toString()));

		this.mockMvc.perform(as(MAREK_TOKEN, MockMvcRequestBuilders.get(EVENTS + id)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startsAt").value(at(this.monday, "16:00").toString()));
	}

	@Test
	void theClientsFreeCancellationDeadlineMovesWithTheTrainingAndTheyAreTold() throws Exception {
		String id = personalTraining(MAREK, "10:00", "11:00");
		UUID customer = book(id, at(this.monday, "10:00").minus(Duration.ofHours(12)));

		putAs(MAREK_TOKEN, id, move(0, "18:00", "19:00", GYM)).andExpect(status().isOk());

		Instant deadline = this.jdbcTemplate.queryForObject(
				"SELECT free_cancel_until FROM reservation WHERE event_id = ?", Timestamp.class, UUID.fromString(id))
			.toInstant();
		assertThat(deadline).isEqualTo(at(this.monday, "18:00").minus(Duration.ofHours(12)));
		assertThat(this.jdbcTemplate.queryForObject(
				"SELECT count(*) FROM schedule_change_notice WHERE event_id = ? AND customer_id = ?", Integer.class,
				UUID.fromString(id), customer))
			.isEqualTo(1);
	}

	@Test
	void onlyTheLeadTrainerCanMoveOnlyTheirPersonalTraining() throws Exception {
		String marekTraining = personalTraining(MAREK, "10:00", "11:00");
		String marekClass = createEvent(eventJson("CLASS", POLE, at(this.monday, "12:00"), at(this.monday, "13:00"),
				staff(MAREK, "LEAD"), room(GYM, true)));
		String assisted = createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday, "14:00"),
				at(this.monday, "15:00"), staff(MAREK, "LEAD") + ", " + staff(ANNA, "ASSISTANT"), room(GYM, true)));

		for (String path : new String[] { marekTraining, marekTraining + "/change-preview" }) {
			String body = move(0, "16:00", "17:00", GYM);
			request(ANNA_TOKEN, path, body).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
			request(TRAINER_TOKEN, path, body).andExpect(status().isNotFound());
			this.anonymous.perform(json(MockMvcRequestBuilders.put(EVENTS + marekTraining), body))
				.andExpect(status().isUnauthorized());
			request(ADMIN_TOKEN, path, body).andExpect(status().isForbidden());
		}
		putAs(MAREK_TOKEN, marekClass, move(0, "16:00", "17:00", GYM)).andExpect(status().isNotFound());
		putAs(ANNA_TOKEN, assisted, move(0, "16:00", "17:00", GYM)).andExpect(status().isNotFound());

		assertThat(startOf(marekTraining)).isEqualTo(at(this.monday, "10:00"));
		assertThat(startOf(marekClass)).isEqualTo(at(this.monday, "12:00"));
		assertThat(startOf(assisted)).isEqualTo(at(this.monday, "14:00"));
	}

	@Test
	void previewShowsConflictsWithoutSavingAndAConflictRejectsTheMove() throws Exception {
		String id = personalTraining(MAREK, "10:00", "11:00");
		String other = createEvent(eventJson("CLASS", POLE, at(this.monday, "16:00"), at(this.monday, "17:00"),
				staff(ANNA, "LEAD"), room(GYM, true)));

		postAs(MAREK_TOKEN, EVENTS + id + "/change-preview", move(0, "16:30", "17:30", GYM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(1))
			.andExpect(jsonPath("$.occurrences[0].issues[0].type").value("RESOURCE_BUSY"))
			.andExpect(jsonPath("$.occurrences[0].issues[0].eventId").value(other));
		// The training does not conflict with itself at an overlapping new time.
		postAs(MAREK_TOKEN, EVENTS + id + "/change-preview", move(0, "10:30", "11:30", GYM))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conflictCount").value(0))
			.andExpect(jsonPath("$.occurrences[0].issues").value(empty()));

		putAs(MAREK_TOKEN, id, move(0, "16:30", "17:30", GYM)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RESOURCE_CAPACITY_EXCEEDED"))
			.andExpect(jsonPath("$.conflicts[0].issues[0].type").value("RESOURCE_BUSY"));
		insertTimeOff(MAREK, at(this.monday, "18:00"), at(this.monday, "20:00"), "APPROVED");
		putAs(MAREK_TOKEN, id, move(0, "18:00", "19:00", GYM)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("STAFF_SCHEDULE_CONFLICT"));
		putAs(MAREK_TOKEN, id, move(0, "12:00", "13:00", POLE_ROOM)).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("SERVICE_NOT_ALLOWED_IN_ROOM"));

		assertThat(startOf(id)).isEqualTo(at(this.monday, "10:00"));
	}

	@Test
	void aStaleVersionAPastTimeOrAStartedTrainingCannotBeMoved() throws Exception {
		String id = personalTraining(MAREK, "10:00", "11:00");

		putAs(MAREK_TOKEN, id, move(3, "16:00", "17:00", GYM)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
		putAs(MAREK_TOKEN, id, """
				{"version": 0, "startsAt": "%s", "endsAt": "%s", "resources": [%s]}
				""".formatted(Instant.now().minus(Duration.ofDays(1)), Instant.now().minus(Duration.ofDays(1))
				.plus(Duration.ofHours(1)), room(GYM, true)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		putAs(MAREK_TOKEN, id, "{\"version\": 0}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		setStatus(id, "CANCELLED");
		putAs(MAREK_TOKEN, id, move(0, "16:00", "17:00", GYM)).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

		assertThat(startOf(id)).isEqualTo(at(this.monday, "10:00"));
	}

	/** A one-off personal training on {@link #monday} in the gym, run by the given trainer. */
	private String personalTraining(UUID trainer, String start, String end) throws Exception {
		return createEvent(eventJson("PERSONAL_TRAINING", PERSONAL, at(this.monday, start), at(this.monday, end),
				staff(trainer, "LEAD"), room(GYM, true)));
	}

	private String move(int version, String start, String end, UUID roomId) {
		return """
				{"version": %d, "startsAt": "%s", "endsAt": "%s", "resources": [%s]}
				""".formatted(version, at(this.monday, start), at(this.monday, end), room(roomId, true));
	}

	private UUID book(String eventId, Instant freeCancelUntil) {
		UUID customer = this.jdbcTemplate.queryForObject(
				"INSERT INTO customer_profile (first_name, last_name) VALUES (?, 'Client') RETURNING id", UUID.class,
				CUSTOMER_NAME);
		UUID bookedBy = this.jdbcTemplate.queryForObject("SELECT id FROM account WHERE email = ?", UUID.class,
				"admin" + TEST_EMAIL_DOMAIN);
		this.jdbcTemplate.update("""
				INSERT INTO reservation (event_id, customer_id, booked_by, free_cancel_until)
				VALUES (?, ?, ?, ?)
				""", UUID.fromString(eventId), customer, bookedBy, Timestamp.from(freeCancelUntil));
		return customer;
	}

	private Instant startOf(String eventId) {
		return this.jdbcTemplate
			.queryForObject("SELECT starts_at FROM schedule_event WHERE id = ?", Timestamp.class,
					UUID.fromString(eventId))
			.toInstant();
	}

	private ResultActions putAs(String token, String eventId, String body) throws Exception {
		return this.mockMvc.perform(as(token, json(MockMvcRequestBuilders.put(EVENTS + eventId), body)));
	}

	private ResultActions postAs(String token, String path, String body) throws Exception {
		return this.mockMvc.perform(as(token, json(MockMvcRequestBuilders.post(path), body)));
	}

	/** A change for an event id, or a preview for a path that ends with {@code /change-preview}. */
	private ResultActions request(String token, String path, String body) throws Exception {
		return path.endsWith("/change-preview") ? postAs(token, EVENTS + path, body) : putAs(token, path, body);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
