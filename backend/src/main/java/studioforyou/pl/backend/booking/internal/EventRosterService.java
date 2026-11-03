package studioforyou.pl.backend.booking.internal;

import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studioforyou.pl.backend.booking.internal.ReservationStore.ActiveReservation;
import studioforyou.pl.backend.customer.CustomerProfiles;
import studioforyou.pl.backend.customer.CustomerProfiles.ProfileDetails;
import studioforyou.pl.backend.scheduling.ScheduledEvents;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.staff.StaffProfiles;

/**
 * Participants of an event for the staff who run or assist it. The staff member comes from the
 * account, never from the request.
 */
@Service
@Transactional(readOnly = true)
class EventRosterService {

	private static final Comparator<EventParticipantResponse> BY_NAME;

	static {
		Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
		BY_NAME = Comparator.comparing(EventParticipantResponse::lastName, collator)
			.thenComparing(EventParticipantResponse::firstName, collator)
			.thenComparing(EventParticipantResponse::bookedAt);
	}

	private final ReservationStore reservations;

	private final ScheduledEvents events;

	private final StaffProfiles staffProfiles;

	private final CustomerProfiles customers;

	EventRosterService(ReservationStore reservations, ScheduledEvents events, StaffProfiles staffProfiles,
			CustomerProfiles customers) {
		this.reservations = reservations;
		this.events = events;
		this.staffProfiles = staffProfiles;
		this.customers = customers;
	}

	/** Participants with an active reservation, sorted by last and first name. */
	List<EventParticipantResponse> participants(UUID accountId, UUID eventId) {
		UUID staffId = this.staffProfiles.getByAccount(accountId).id();
		if (!this.events.isAssigned(eventId, staffId)) {
			// The same answer as for a missing event, so other trainers' events stay hidden.
			throw new ApiException(ErrorCode.NOT_FOUND, "Schedule event not found.");
		}
		List<ActiveReservation> active = this.reservations.activeReservations(eventId);
		Map<UUID, ProfileDetails> profiles = this.customers
			.findAllById(active.stream().map(ActiveReservation::customerId).toList())
			.stream()
			.collect(Collectors.toMap(ProfileDetails::id, Function.identity()));
		return active.stream()
			.filter((reservation) -> profiles.containsKey(reservation.customerId()))
			.map((reservation) -> EventParticipantResponse.from(reservation, profiles.get(reservation.customerId())))
			.sorted(BY_NAME)
			.toList();
	}

}
