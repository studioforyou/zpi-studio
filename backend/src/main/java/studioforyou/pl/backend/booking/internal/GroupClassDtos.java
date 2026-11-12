package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import studioforyou.pl.backend.scheduling.SeriesChange;

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
			BookingAvailability booking, WaitlistAvailability waitlist, int version,
			@Schema(description = "How a one-off change makes this class differ from its series: time, lead "
					+ "trainers or rooms; null when it is as usual.") SeriesChange seriesChange) {
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

	@Schema(name = "GroupClassWaitlist")
	record WaitlistAvailability(
			@Schema(description = "Whether this profile can join the waiting list now: the class is full and the profile could book it otherwise.")
			boolean allowed,
			@Schema(description = "Stable reason code, null when joining is allowed. CLASS_NOT_FULL means a place can be booked.")
			String reason,
			@Schema(description = "Participants waiting for a place.") int waitingCount,
			@Schema(description = "Minutes a participant has to confirm a place offered from the list; 0 books it at once.")
			int confirmationMinutes,
			@Schema(description = "This profile's place in the queue or its offered place; null when it is not on the list.")
			WaitlistEntryResponse ownEntry) {
	}

	@Schema(name = "OwnGroupClassWaitlistEntry")
	record WaitlistEntryResponse(UUID id, UUID eventId, UUID customerId,
			@Schema(description = "WAITING; OFFERED (a freed place is held until offerExpiresAt for the participant to confirm); PROMOTED (got the place from the list); BOOKED (booked a free place directly); CANCELLED (left the list); or EXPIRED (did not confirm in time).")
			String status,
			@Schema(description = "The pass chosen when joining. A promotion charges it or, when it can no longer be used, another eligible pass.")
			UUID customerPassId, Instant joinedAt, Instant resolvedAt,
			@Schema(description = "The reservation the participant got while on the list; null otherwise.") UUID reservationId,
			@Schema(description = "1-based place among the participants still waiting; null when a place is offered or the entry is resolved.")
			Integer position,
			@Schema(description = "Until when an offered place can be confirmed; null unless OFFERED.") Instant offerExpiresAt,
			@Schema(description = "Why the participant would be skipped if a place freed up now, with the booking reason codes or CUSTOMER_NOT_ACTIVE; null when they would get it or no longer wait.")
			String promotionBlockedReason) {

		static WaitlistEntryResponse of(WaitlistStore.WaitlistEntry entry, Integer position, String promotionBlockedReason) {
			return new WaitlistEntryResponse(entry.id(), entry.eventId(), entry.customerId(), entry.status(),
					entry.customerPassId(), entry.joinedAt(), entry.resolvedAt(), entry.reservationId(), position,
					WaitlistStore.OFFERED.equals(entry.status()) ? entry.offerExpiresAt() : null, promotionBlockedReason);
		}

	}

	@Schema(name = "OwnGroupClassReservation")
	record ReservationResponse(UUID id, UUID eventId, UUID customerId, String status, UUID customerPassId,
			Instant freeCancelUntil, Instant cancelledAt, String cancelKind,
			@Schema(description = "Actual entry-return result after cancellation; null for an active reservation.")
			Boolean entryReturned, CancellationAvailability cancellation, int version, UUID makeupCreditId) {
	}

	@Schema(name = "GroupClassCancellationAvailability")
	record CancellationAvailability(boolean allowed, String reason,
			@Schema(description = "Current consequence to confirm; null when cancellation is unavailable or the reservation "
					+ "has no pass (an administrator booked it without a pass entry, so there is no entry to return).")
			Boolean entryReturned) {
	}

	@Schema(name = "TrainingHistoryEntry")
	record HistoryEntryResponse(UUID reservationId, UUID eventId, String title, String serviceTypeName,
			@Schema(description = "The service definition's configured #RRGGBB color, or null when unset.") String calendarColor,
			Instant startsAt, Instant endsAt, List<InstructorResponse> staff, List<RoomResponse> resources,
			@Schema(description = "PRESENT, ABSENT, UNMARKED (attendance not recorded yet), CANCELLED (in time), LATE_CANCELLED, or CLASS_CANCELLED (by the studio).")
			String outcome,
			@Schema(description = "Whether the customer's cancellation returned the pass entry; null without one.")
			Boolean entryReturned, String cancellationEffect) {
	}

	@Schema(name = "BookGroupClassRequest")
	record BookRequest(@NotNull UUID customerPassId) {
	}

	@Schema(name = "JoinGroupClassWaitlistRequest")
	record JoinWaitlistRequest(
			@Schema(description = "One of the profile's eligible passes, charged when a place is given from the list.")
			@NotNull UUID customerPassId) {
	}

	@Schema(name = "CancelOwnGroupClassReservationRequest")
	record CancelRequest(@NotNull @Min(0) Integer expectedVersion,
			@Schema(description = "The entry-return consequence the customer confirmed: cancellation.entryReturned, null for a "
					+ "reservation without a pass. A changed consequence rejects the cancellation.")
			Boolean expectedEntryReturned) {
	}

}
