package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Customer projections leave out planner details and expose participant names only through a guarded roster. */
final class GroupClassDtos {

	private GroupClassDtos() {
	}

	@Schema(name = "CustomerGroupClass")
	record GroupClassResponse(UUID id, String title, UUID serviceTypeId, String serviceTypeName,
			@Schema(description = "The service definition's configured #RRGGBB color, or null when unset.") String calendarColor,
			Instant startsAt, Instant endsAt, String status, Integer capacity, int activeReservations,
			@Schema(description = "null means that the event has no capacity limit.") Integer availablePlaces,
			List<InstructorResponse> staff, List<RoomResponse> resources, ReservationResponse ownReservation,
			BookingAvailability booking, int version) {
	}

	@Schema(name = "GroupClassParticipant")
	record ParticipantResponse(String displayName, boolean isSelf) {
	}

	@Schema(name = "CustomerGroupClassInstructor")
	record InstructorResponse(UUID staffId, String displayName, String role) {
	}

	@Schema(name = "CustomerGroupClassRoom")
	record RoomResponse(UUID resourceId, String name) {
	}

	@Schema(name = "GroupClassBookingAvailability")
	record BookingAvailability(boolean allowed,
			@Schema(description = "Stable reason code, null when booking is allowed.") String reason,
			List<EligiblePassResponse> eligiblePasses) {
	}

	@Schema(name = "GroupClassEligiblePass")
	record EligiblePassResponse(UUID id, String productName, Short entriesRemaining, LocalDate validUntil) {
	}

	@Schema(name = "OwnGroupClassReservation")
	record ReservationResponse(UUID id, UUID eventId, UUID customerId, String status, UUID customerPassId,
			Instant freeCancelUntil, Instant cancelledAt, String cancelKind,
			@Schema(description = "Actual entry-return result after cancellation; null for an active reservation.")
			Boolean entryReturned, CancellationAvailability cancellation, int version) {
	}

	@Schema(name = "GroupClassCancellationAvailability")
	record CancellationAvailability(boolean allowed, String reason,
			@Schema(description = "Current consequence to confirm; null when cancellation is unavailable.")
			Boolean entryReturned) {
	}

	@Schema(name = "BookGroupClassRequest")
	record BookRequest(@NotNull UUID customerPassId) {
	}

	@Schema(name = "CancelOwnGroupClassReservationRequest")
	record CancelRequest(@NotNull @Min(0) Integer expectedVersion,
			@Schema(description = "The entry-return consequence the customer confirmed. A changed consequence rejects the cancellation.")
			@NotNull Boolean expectedEntryReturned) {
	}

}
