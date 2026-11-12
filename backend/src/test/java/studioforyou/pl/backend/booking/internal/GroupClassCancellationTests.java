package studioforyou.pl.backend.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import studioforyou.pl.backend.booking.internal.ReservationStore.PastReservation;
import studioforyou.pl.backend.booking.internal.ReservationStore.Reservation;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;

class GroupClassCancellationTests {

	private static final Instant START = Instant.parse("2027-01-12T17:00:00Z");

	private static final Instant DEADLINE = START.minusSeconds(12 * 3600);

	@Test
	void theExactFreeDeadlineReturnsAnEntryAndTheFollowingInstantDoesNot() {
		var reservation = reservation(true, true, true);
		assertThat(GroupClassBookingService.cancellation(reservation, event(), DEADLINE).entryReturned()).isTrue();
		assertThat(GroupClassBookingService.cancellation(reservation, event(), DEADLINE.plusNanos(1)).entryReturned()).isFalse();
	}

	@Test
	void lateCancellationCanBeForbiddenOrAllowedWithoutForfeitingAnEntry() {
		var forbidden = GroupClassBookingService.cancellation(reservation(true, false, true), event(), DEADLINE.plusSeconds(1));
		assertThat(forbidden.allowed()).isFalse();
		assertThat(forbidden.reason()).isEqualTo("FREE_CANCELLATION_EXPIRED");
		assertThat(GroupClassBookingService.cancellation(reservation(true, true, false), event(), DEADLINE.plusSeconds(1)).entryReturned()).isTrue();
	}

	@Test
	void cancellationIsUnavailableAtTheStartAndWhenTheSnapshotDisallowsIt() {
		assertThat(GroupClassBookingService.cancellation(reservation(true, true, true), event(), START).reason())
			.isEqualTo("EVENT_ALREADY_STARTED");
		assertThat(GroupClassBookingService.cancellation(reservation(false, true, true), event(), DEADLINE).reason())
			.isEqualTo("CANCELLATION_NOT_ALLOWED");
	}

	@Test
	void aReservationWithoutAPassIsCancelledOnItsTermsWithoutAnEntryConsequence() {
		var passless = new Reservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", null,
				DEADLINE, null, null, null, true, false, true, 0, null);
		var inTime = GroupClassBookingService.cancellation(passless, event(), DEADLINE);
		assertThat(inTime.allowed()).isTrue();
		assertThat(inTime.entryReturned()).isNull();
		assertThat(GroupClassBookingService.cancellation(passless, event(), DEADLINE.plusSeconds(1)).reason())
			.isEqualTo("FREE_CANCELLATION_EXPIRED");
		assertThat(StaffBookingService.suggestedEntryReturned(passless, DEADLINE)).isNull();
	}

	@Test
	void administratorsCancelUntilTheStartWithTheEntryTheTermsSuggest() {
		var forfeiting = reservation(true, false, true);
		assertThat(StaffBookingService.suggestedEntryReturned(forfeiting, DEADLINE)).isTrue();
		assertThat(StaffBookingService.suggestedEntryReturned(forfeiting, DEADLINE.plusSeconds(1))).isFalse();
		assertThat(StaffBookingService.suggestedEntryReturned(reservation(true, true, false), DEADLINE.plusSeconds(1)))
			.isTrue();
		// The participant could not cancel late, but an administrator can until the start.
		assertThat(StaffBookingService.cancellationReason(forfeiting, event(), DEADLINE.plusSeconds(1))).isNull();
		assertThat(StaffBookingService.cancellationReason(reservation(false, true, true), event(), DEADLINE)).isNull();
		assertThat(StaffBookingService.cancellationReason(forfeiting, event(), START)).isEqualTo("EVENT_ALREADY_STARTED");
		assertThat(StaffBookingService.cancellationReason(forfeiting, event("CANCELLED"), DEADLINE))
			.isEqualTo("EVENT_NOT_BOOKABLE");
		assertThat(StaffBookingService.bookableReason(event(), START.minusNanos(1))).isNull();
		assertThat(StaffBookingService.bookableReason(event(), START)).isEqualTo("EVENT_NOT_BOOKABLE");
		assertThat(StaffBookingService.bookableReason(event("COMPLETED"), DEADLINE)).isEqualTo("EVENT_NOT_BOOKABLE");
	}

	@Test
	void ageUsesTheOccurrenceDayIncludingTheBirthdayAndHandlesMissingBirthDates() {
		LocalDate birth = LocalDate.of(2015, 4, 12);
		assertThat(ParticipationRules.ageAllowed(birth, LocalDate.of(2027, 4, 11), (short) 12, (short) 15)).isFalse();
		assertThat(ParticipationRules.ageAllowed(birth, LocalDate.of(2027, 4, 12), (short) 12, (short) 15)).isTrue();
		assertThat(ParticipationRules.ageAllowed(null, LocalDate.of(2027, 4, 12), (short) 12, null)).isFalse();
		assertThat(ParticipationRules.ageAllowed(null, LocalDate.of(2027, 4, 12), null, null)).isTrue();
	}

	@Test
	void ownCancellationOutranksTheStudiosWhichOutranksAttendance() {
		assertThat(GroupClassBookingService.outcome(past("CANCELLED", "LATE", "UNMARKED"), event("CANCELLED")))
			.isEqualTo("LATE_CANCELLED");
		assertThat(GroupClassBookingService.outcome(past("CANCELLED", "IN_TIME", "UNMARKED"), event()))
			.isEqualTo("CANCELLED");
		assertThat(GroupClassBookingService.outcome(past("ACTIVE", null, "PRESENT"), event("CANCELLED")))
			.isEqualTo("CLASS_CANCELLED");
		assertThat(GroupClassBookingService.outcome(past("ACTIVE", null, "PRESENT"), event())).isEqualTo("PRESENT");
		assertThat(GroupClassBookingService.outcome(past("ACTIVE", null, "ABSENT"), event())).isEqualTo("ABSENT");
		assertThat(GroupClassBookingService.outcome(past("ACTIVE", null, "UNMARKED"), event())).isEqualTo("UNMARKED");
	}

	private static PastReservation past(String status, String cancelKind, String attendance) {
		return new PastReservation(UUID.randomUUID(), UUID.randomUUID(), status, cancelKind, null, attendance, null);
	}

	private static Reservation reservation(Boolean allowed, Boolean lateAllowed, Boolean forfeits) {
		return new Reservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", UUID.randomUUID(),
				DEADLINE, null, null, null, allowed, lateAllowed, forfeits, 0, null);
	}

	private static Occurrence event() {
		return event("SCHEDULED");
	}

	private static Occurrence event(String status) {
		return new Occurrence(UUID.randomUUID(), "Class", UUID.randomUUID(), "Service", null, null, START,
				START.plusSeconds(3600), status, 12, List.of(), List.of(), 0);
	}

}
