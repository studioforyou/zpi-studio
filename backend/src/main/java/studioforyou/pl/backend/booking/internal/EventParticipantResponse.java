package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import studioforyou.pl.backend.booking.internal.ReservationStore.ActiveReservation;
import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;

/**
 * A participant with an active reservation, as the staff running the event see them. No contact
 * details.
 *
 * @param reservationId the participant's place in the event
 * @param customerId the participant's profile
 * @param birthDate the date of birth, or {@code null} if unknown
 * @param bookedAt when the place was reserved
 */
@Schema(name = "EventParticipant")
record EventParticipantResponse(UUID reservationId, UUID customerId, CustomerKind kind,
		@Schema(example = "Katarzyna") String firstName, @Schema(example = "Zielińska") String lastName,
		@Schema(example = "Katarzyna Zielińska") String displayName, LocalDate birthDate, Instant bookedAt) {

	static EventParticipantResponse from(ActiveReservation reservation, ProfileDetails customer) {
		return new EventParticipantResponse(reservation.id(), customer.id(), customer.kind(), customer.firstName(),
				customer.lastName(), customer.displayName(), customer.birthDate(), reservation.bookedAt());
	}

}
