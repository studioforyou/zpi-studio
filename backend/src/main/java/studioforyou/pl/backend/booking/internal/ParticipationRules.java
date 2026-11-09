package studioforyou.pl.backend.booking.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import studioforyou.pl.backend.booking.internal.WaitlistStore.WaitlistEntry;
import studioforyou.pl.backend.configuration.BookingPolicies;
import studioforyou.pl.backend.configuration.StudioSettings;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.documents.DocumentRequirements;
import studioforyou.pl.backend.membership.BookingEntitlements;
import studioforyou.pl.backend.membership.BookingEntitlements.EligiblePass;
import studioforyou.pl.backend.offering.ServiceTypes;
import studioforyou.pl.backend.scheduling.GroupClassSchedule.Occurrence;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/**
 * What a participant needs to take part in a group class. Booking, the waiting list and its
 * automatic promotion share these checks, so they always decide alike. Nothing here locks or
 * changes data.
 */
@Component
class ParticipationRules {

	private final CustomerProfiles customers;

	private final ServiceTypes services;

	private final BookingPolicies policies;

	private final BookingEntitlements entitlements;

	private final DocumentRequirements documents;

	private final StudioSettings studio;

	ParticipationRules(CustomerProfiles customers, ServiceTypes services, BookingPolicies policies,
			BookingEntitlements entitlements, DocumentRequirements documents, StudioSettings studio) {
		this.customers = customers;
		this.services = services;
		this.policies = policies;
		this.entitlements = entitlements;
		this.documents = documents;
		this.studio = studio;
	}

	/** The participant the account acts for: its own active profile or a child it represents. */
	ProfileDetails participant(UUID actorId, UUID profileId) {
		if (!this.customers.isActingFor(actorId, profileId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Participant not found.");
		}
		return this.customers.findById(profileId)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Participant not found."));
	}

	/** The occurrence's current booking terms and its date in the studio's time zone. */
	Terms terms(Occurrence event) {
		return new Terms(this.policies.forOccurrence(event.serviceTypeId(), event.trainingGroupId(), event.id()),
				LocalDate.ofInstant(event.startsAt(), this.studio.zone()));
	}

	/** The participant's passes that can fund the occurrence, in membership's order. */
	List<EligiblePass> passes(UUID customerId, Occurrence event, LocalDate date) {
		return this.entitlements.eligible(customerId, event.serviceTypeId(), date);
	}

	/** Why nobody can book the occurrence now (its status, start or booking window), or null. */
	static String windowReason(Occurrence event, BookingPolicies.Values rules, Instant now) {
		if (!"SCHEDULED".equals(event.status()) || !event.startsAt().isAfter(now)) {
			return "EVENT_NOT_BOOKABLE";
		}
		if (now.isBefore(event.startsAt().minus(Duration.ofDays(rules.bookingOpensDaysBefore())))) {
			return "BOOKING_NOT_OPEN";
		}
		if (!now.isBefore(event.startsAt().minus(Duration.ofMinutes(rules.bookingClosesMinutesBefore())))) {
			return "BOOKING_CLOSED";
		}
		return null;
	}

	/** Why this participant cannot take part (service, age, documents or funding), or null. */
	String participantReason(ProfileDetails participant, Occurrence event, LocalDate date, List<EligiblePass> passes) {
		return participantReason(participant, event, date, !passes.isEmpty());
	}

	/** A make-up credit can fund the class without requiring another active pass. */
	String participantReason(ProfileDetails participant, Occurrence event, LocalDate date, boolean funded) {
		List<String> unmet = unmetRequirements(participant, event, date, funded, true);
		return unmet.isEmpty() ? null : unmet.getFirst();
	}

	/**
	 * Every requirement of the class this participant does not meet, in the order
	 * {@link #participantReason} checks them, for an administrator who may override them.
	 */
	List<String> unmetRequirements(ProfileDetails participant, Occurrence event, LocalDate date,
			List<EligiblePass> passes) {
		return unmetRequirements(participant, event, date, !passes.isEmpty(), false);
	}

	private List<String> unmetRequirements(ProfileDetails participant, Occurrence event, LocalDate date,
			boolean funded, boolean firstOnly) {
		List<String> unmet = new ArrayList<>();
		ServiceTypes.Definition service = this.services.findDefinitionById(event.serviceTypeId()).orElse(null);
		if (service == null || !service.active()) {
			unmet.add("SERVICE_TYPE_UNAVAILABLE");
		}
		if (service != null && !ageAllowed(participant.birthDate(), date, service.minAge(), service.maxAge())) {
			unmet.add("AGE_RESTRICTION");
		}
		// Each check costs a query, so self-service stops at the first unmet requirement.
		if (firstOnly && !unmet.isEmpty()) {
			return unmet.subList(0, 1);
		}
		if (this.documents.hasMissing(participant.id(), event.serviceTypeId(), participant.kind().name())) {
			unmet.add("REQUIRED_DOCUMENTS_MISSING");
		}
		if (!funded) {
			unmet.add("NO_ELIGIBLE_PASS");
		}
		return unmet;
	}

	/**
	 * Why a waiting participant would be skipped if a place freed up now, or null. The automatic
	 * promotion and the participant's own view of the entry both decide with this. The account
	 * that joined must still act for the participant, as it is the one the place is booked by.
	 */
	String promotionReason(WaitlistEntry entry, ProfileDetails participant, Occurrence event, Terms terms,
			List<EligiblePass> passes, Instant now) {
		if (!this.customers.isActingFor(entry.addedBy(), entry.customerId())) {
			return "CUSTOMER_NOT_ACTIVE";
		}
		String window = windowReason(event, terms.rules(), now);
		return window != null ? window : participantReason(participant, event, terms.date(), passes);
	}

	/** The pass chosen when joining while it can fund the occurrence, otherwise the first eligible one. */
	static EligiblePass fundingPass(UUID chosenPassId, List<EligiblePass> passes) {
		return passes.stream().filter(pass -> pass.id().equals(chosenPassId)).findFirst()
			.orElse(passes.isEmpty() ? null : passes.getFirst());
	}

	static boolean ageAllowed(LocalDate birthDate, LocalDate occurrenceDate, Short minAge, Short maxAge) {
		if (minAge == null && maxAge == null) {
			return true;
		}
		if (birthDate == null || birthDate.isAfter(occurrenceDate)) {
			return false;
		}
		int age = Period.between(birthDate, occurrenceDate).getYears();
		return (minAge == null || age >= minAge) && (maxAge == null || age <= maxAge);
	}

	static ApiException rejected(String reason) {
		return new ApiException(ErrorCode.valueOf(reason), "The requested operation is unavailable: " + reason + ".");
	}

	/** The resolved booking policy of an occurrence and its date in the studio's time zone. */
	record Terms(BookingPolicies.Definition policy, LocalDate date) {

		BookingPolicies.Values rules() {
			return this.policy.effective();
		}

		/** The free cancellation deadline a reservation made now keeps as its snapshot. */
		Instant freeCancelUntil(Occurrence event) {
			return event.startsAt().minus(Duration.ofHours(rules().freeCancelHours()));
		}

	}

}
