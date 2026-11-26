package studioforyou.pl.backend.booking.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.customer.CustomerKind;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeEffect;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeFunding;
import studioforyou.pl.backend.membership.PersonalTrainingAccounting.DisputeResolution;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Instructor;

/** Request and response bodies of the administrators' personal-training dispute endpoints. */
final class PersonalTrainingDisputeDtos {

	static final int MAX_NOTE_LENGTH = 1000;

	static final int MAX_MAKEUP_VALIDITY_DAYS = 365;

	private PersonalTrainingDisputeDtos() {
	}

	/** {@code training_dispute.status}. */
	enum DisputeStatus {

		OPEN, RESOLVED

	}

	@Schema(name = "PersonalTrainingDisputeCase", description = "A client's dispute about a held personal session, "
			+ "with the session, the participant and the decision.")
	record DisputeResponse(UUID id,
			@Schema(description = "OPEN while it waits for a decision, then RESOLVED.", example = "OPEN") String status,
			@Schema(description = "What the client reported.", example = "Trening trwał 30 minut zamiast 60.") String reason,
			Instant raisedAt,
			@Schema(description = "Who reported it: the participant or their guardian; null when unknown.",
					example = "Monika Dąbrowska") @Nullable String raisedByName,
			UUID reservationId,
			@Schema(description = "The reservation's version, sent back with the decision.") int reservationVersion,
			UUID customerId, CustomerKind customerKind,
			@Schema(example = "Tomasz Lewandowski") String customerName,
			@Schema(description = "The account that represents the participant: their own or, for a child, the "
					+ "guardian's; null without one.") @Nullable UUID customerAccountId,
			UUID eventId, String title, String serviceTypeName, Instant startsAt, Instant endsAt,
			@Schema(description = "SCHEDULED, COMPLETED or CANCELLED.") String eventStatus,
			List<Instructor> staff,
			@Schema(description = "UNMARKED, PRESENT or ABSENT.") String attendance,
			@Schema(description = "When the trainer marked the session as held.") @Nullable Instant trainerConfirmedAt,
			@Nullable DisputeResolution resolution,
			@Schema(description = "What the decision did to the session's funding.") @Nullable DisputeEffect resolutionEffect,
			@Schema(description = "The administrator's explanation, shown to the client.") @Nullable String resolutionNote,
			@Nullable Instant resolvedAt,
			@Schema(description = "Who decided; null when unknown.", example = "Beata Lis") @Nullable String resolvedByName,
			@Schema(description = "What each decision would do; only on a single open dispute.") @Nullable Options options) {
	}

	@Schema(name = "PersonalTrainingDisputeOptions", description = "How the session was paid for and what each "
			+ "decision would do to it.")
	record Options(
			@Schema(description = "PASS (with entries), UNLIMITED_PASS, MAKEUP (a make-up credit) or NONE (not paid "
					+ "for in the application).") DisputeFunding funding,
			@Schema(description = "The funding pass, or the pass the make-up credit came from.") @Nullable String passName,
			@Schema(description = "The funding pass's balance now.") @Nullable Short entriesRemaining,
			@Nullable LocalDate passValidUntil,
			@Schema(description = "When the make-up credit that paid for the session expires.") @Nullable Instant makeupExpiresAt,
			Option upheld, Option upheldMakeup, Option rejected,
			@Schema(description = "The suggested validity of a granted make-up credit, from the product's make-up "
					+ "policy or 30 days; null when no credit can be granted.") @Nullable Integer makeupValidityDays) {
	}

	@Schema(name = "PersonalTrainingDisputeOption")
	record Option(boolean allowed,
			@Schema(description = "ENTRY_RETURNED, MAKEUP_RETURNED, MAKEUP_GRANTED or NO_CHANGE.") DisputeEffect effect,
			@Schema(description = "Why it is not available, or what to know before choosing it: PASS_EXPIRED, "
					+ "PASS_CANCELLED, NO_ALLOCATED_ENTRY, UNLIMITED_PASS, NOT_FUNDED, MAKEUP_EXPIRED or "
					+ "MAKEUP_NOT_USABLE.") @Nullable String notice) {
	}

	@Schema(name = "ResolvePersonalTrainingDisputeRequest")
	record ResolveRequest(
			@Schema(description = "UPHELD gives the entry or make-up credit back; UPHELD_MAKEUP grants a new make-up "
					+ "credit and keeps the entry used; REJECTED keeps the entry used.") @NotNull DisputeResolution resolution,
			@Schema(description = "The explanation shown to the client.", example = "Trener potwierdził skrócenie zajęć.")
			@NotBlank @Size(max = MAX_NOTE_LENGTH) String note,
			@Schema(description = "Required for UPHELD_MAKEUP: how many days the new credit is valid.", example = "30")
			@Min(1) @Max(MAX_MAKEUP_VALIDITY_DAYS) @Nullable Integer makeupValidityDays,
			@Schema(description = "The reservation's current version; a stale value returns 409 VERSION_CONFLICT.")
			@NotNull @Min(0) Integer version) {
	}

}
