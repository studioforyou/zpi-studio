package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import studioforyou.pl.backend.membership.PersonalTrainingAccounting.Effect;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Instructor;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Room;

final class PersonalTrainingDtos {
	private PersonalTrainingDtos() {}

	@Schema(name = "OwnPersonalTraining")
	record Session(UUID id, UUID reservationId, String kind, String status, String title, UUID serviceTypeId,
			String serviceTypeName, Instant startsAt, Instant endsAt, List<Instructor> staff, List<Room> resources,
			String passName, String attendance, Instant trainerConfirmedAt, String settlementStatus,
			String cancellationEffect, Cancellation cancellation, DisputeAvailability disputeAvailability,
			Dispute dispute, int version) {}

	@Schema(name = "PersonalTrainingCancellation")
	record Cancellation(boolean allowed, String reason, Instant freeCancelUntil, boolean late,
			Effect effect, Integer validityDays, String consequenceReason) {}

	@Schema(name = "PersonalTrainingDisputeAvailability")
	record DisputeAvailability(boolean allowed, String reason, Instant closesAt) {}

	@Schema(name = "PersonalTrainingDispute")
	record Dispute(UUID id, String status, String reason, Instant raisedAt, String resolution,
			String resolutionNote, Instant resolvedAt) {}

	@Schema(name = "CancelPersonalTrainingRequest")
	record CancelRequest(@NotNull @Min(0) Integer expectedVersion, @NotNull Effect expectedEffect,
			@Min(1) Integer expectedValidityDays) {}

	@Schema(name = "PersonalTrainingDisputeRequest")
	record DisputeRequest(@NotNull @Min(0) Integer expectedVersion, @NotBlank @Size(max = 1000) String reason) {}
}
