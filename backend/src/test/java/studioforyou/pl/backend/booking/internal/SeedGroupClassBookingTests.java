package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.TestcontainersConfiguration;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancelRequest;

/** A fresh development seed supports the same flow used during manual acceptance. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("seed")
@Transactional
class SeedGroupClassBookingTests {

	@Autowired GroupClassBookingService bookings;
	@Autowired JdbcClient jdbc;
	@Autowired DataSource dataSource;

	@Test
	void showcaseUsesExistingAccountsWithSparseRealBookingsAndParallelClasses() {
		assertThat(jdbc.sql("""
				SELECT count(*) FROM schedule_event
				WHERE id BETWEEN '19000000-0000-0000-0000-000000000101'::uuid
				             AND '19000000-0000-0000-0000-000000000226'::uuid
				""").query(Integer.class).single()).isEqualTo(52);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM event_staff
				WHERE event_id BETWEEN '19000000-0000-0000-0000-000000000101'::uuid
				                   AND '19000000-0000-0000-0000-000000000226'::uuid
				""").query(Integer.class).single()).isEqualTo(52);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM event_resource
				WHERE event_id BETWEEN '19000000-0000-0000-0000-000000000101'::uuid
				                   AND '19000000-0000-0000-0000-000000000226'::uuid
				""").query(Integer.class).single()).isEqualTo(52);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM schedule_event
				WHERE starts_at = (SELECT starts_at FROM schedule_event
				                    WHERE id = '19000000-0000-0000-0000-000000000101')
				""").query(Integer.class).single()).isGreaterThanOrEqualTo(3);
		UUID actor = UUID.fromString("03000000-0000-0000-0000-000000000004");
		UUID profile = UUID.fromString("07000000-0000-0000-0000-000000000001");
		assertThat(bookings.get(actor, profile, UUID.fromString("19000000-0000-0000-0000-000000000101"))
			.activeReservations()).isEqualTo(2);
		assertThat(bookings.get(actor, profile, UUID.fromString("19000000-0000-0000-0000-000000000003"))
			.availablePlaces()).isZero();
		assertThat(bookings.get(actor, profile, UUID.fromString("19000000-0000-0000-0000-000000000106"))
			.activeReservations()).isZero();
		assertThat(jdbc.sql("""
				SELECT count(*) FROM customer_pass p
				WHERE p.id BETWEEN '28000000-0000-0000-0000-000000001001'::uuid
				               AND '28000000-0000-0000-0000-000000001208'::uuid
				AND p.entries_remaining IS NOT NULL AND p.entries_remaining <>
				    (SELECT coalesce(sum(entries_delta), 0) FROM pass_ledger WHERE pass_id = p.id)
				""").query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM account").query(Integer.class).single()).isEqualTo(10);
		assertThat(jdbc.sql("SELECT count(*) FROM customer_profile").query(Integer.class).single()).isEqualTo(8);
		assertThat(jdbc.sql("SELECT count(*) FROM staff_profile").query(Integer.class).single()).isEqualTo(4);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM reservation r JOIN schedule_event e ON e.id = r.event_id
				WHERE e.id BETWEEN '19000000-0000-0000-0000-000000000101'::uuid
				               AND '19000000-0000-0000-0000-000000000226'::uuid
				AND r.status = 'ACTIVE'
				""").query(Integer.class).single()).isEqualTo(7);
		UUID shared = UUID.fromString("19000000-0000-0000-0000-000000000101");
		assertThat(bookings.participants(actor, profile, shared))
			.extracting(GroupClassDtos.ParticipantResponse::displayName)
			.containsExactlyInAnyOrder("Katarzyna Zielińska", "Monika Dąbrowska");
		assertThat(bookings.get(UUID.fromString("03000000-0000-0000-0000-000000000006"),
			UUID.fromString("07000000-0000-0000-0000-000000000003"), shared).ownReservation()).isNotNull();
	}

	@Test
	void showcaseRerunPreservesEditedClassesAndInstructorAndRoomAssignments() {
		UUID event = UUID.fromString("19000000-0000-0000-0000-000000000101");
		UUID substitute = UUID.fromString("08000000-0000-0000-0000-000000000003");
		UUID replacementRoom = UUID.fromString("15000000-0000-0000-0000-000000000003");
		jdbc.sql("""
				UPDATE schedule_event
				SET title = 'Adjusted during manual testing', capacity = 6,
				    starts_at = starts_at + interval '1 day', ends_at = ends_at + interval '1 day'
				WHERE id = :id
				""").param("id", event).update();
		var before = jdbc.sql("SELECT title, capacity, starts_at, ends_at FROM schedule_event WHERE id = :id")
			.param("id", event).query().singleRow();
		jdbc.sql("UPDATE event_staff SET staff_id = :staff WHERE event_id = :event")
			.param("staff", substitute).param("event", event).update();
		jdbc.sql("UPDATE event_resource SET resource_id = :room WHERE event_id = :event")
			.param("room", replacementRoom).param("event", event).update();

		new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_08_timetable_showcase.sql"))
			.execute(dataSource);

		assertThat(jdbc.sql("SELECT title, capacity, starts_at, ends_at FROM schedule_event WHERE id = :id")
			.param("id", event).query().singleRow()).isEqualTo(before);
		assertThat(jdbc.sql("SELECT staff_id FROM event_staff WHERE event_id = :id")
			.param("id", event).query(UUID.class).list()).containsExactly(substitute);
		assertThat(jdbc.sql("SELECT resource_id FROM event_resource WHERE event_id = :id")
			.param("id", event).query(UUID.class).list()).containsExactly(replacementRoom);
	}

	@Test
	void showcaseCancellationAndSeedRerunKeepTesterChanges() {
		UUID actor = UUID.fromString("03000000-0000-0000-0000-000000000006");
		UUID child = UUID.fromString("07000000-0000-0000-0000-000000000004");
		UUID event = UUID.fromString("19000000-0000-0000-0000-000000000121");
		UUID pass = UUID.fromString("28000000-0000-0000-0000-000000001004");
		var detail = bookings.get(actor, child, event);
		assertThat(bookings.participants(actor, child, event)).hasSize(1);
		bookings.cancel(actor, child, detail.ownReservation().id(), new CancelRequest(0, true));
		new ResourceDatabasePopulator(new ClassPathResource("db/seed/R__seed_08_timetable_showcase.sql"))
			.execute(dataSource);
		assertThat(bookings.get(actor, child, event).ownReservation()).isNull();
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id")
			.param("id", pass).query(Integer.class).single()).isEqualTo(4);
		bookings.book(actor, child, event, pass);
		assertThat(bookings.get(actor, child, event).ownReservation()).isNotNull();
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id")
			.param("id", pass).query(Integer.class).single()).isEqualTo(3);
	}

	@Test
	void existingClientsCanCancelTheirOriginalClassesWithUnlimitedPasses() {
		for (int[] fixture : new int[][] { { 6, 3, 3 }, { 5, 2, 5 } }) {
			UUID actor = UUID.fromString("03000000-0000-0000-0000-%012d".formatted(fixture[0]));
			UUID profile = UUID.fromString("07000000-0000-0000-0000-%012d".formatted(fixture[1]));
			UUID event = UUID.fromString("19000000-0000-0000-0000-%012d".formatted(fixture[2]));
			var own = bookings.get(actor, profile, event).ownReservation();
			assertThat(own.cancellation().allowed()).isTrue();
			bookings.cancel(actor, profile, own.id(), new CancelRequest(own.version(), true));
			bookings.book(actor, profile, event, own.customerPassId());
			assertThat(bookings.get(actor, profile, event).ownReservation()).isNotNull();
		}
	}

	@Test
	void seededClientCanBrowseCancelAndRebookWithTheRealPassAndDocumentRequirements() {
		UUID actor = UUID.fromString("03000000-0000-0000-0000-000000000004");
		UUID profile = UUID.fromString("07000000-0000-0000-0000-000000000001");
		UUID event = UUID.fromString("19000000-0000-0000-0000-000000000002");
		UUID pass = UUID.fromString("28000000-0000-0000-0000-000000000001");
		var offer = bookings.list(actor, profile, Instant.now(), Instant.now().plus(Duration.ofDays(20)), false);
		assertThat(offer).isNotEmpty();
		var detail = bookings.get(actor, profile, event);
		assertThat(detail.ownReservation().cancellation().allowed()).isTrue();
		int before = jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id")
			.param("id", pass).query(Integer.class).single();
		bookings.cancel(actor, profile, detail.ownReservation().id(), new CancelRequest(0, true));
		assertThat(bookings.get(actor, profile, event).booking().allowed()).isTrue();
		bookings.book(actor, profile, event, pass);
		assertThat(jdbc.sql("SELECT entries_remaining FROM customer_pass WHERE id = :id")
			.param("id", pass).query(Integer.class).single()).isEqualTo(before);
	}

}
