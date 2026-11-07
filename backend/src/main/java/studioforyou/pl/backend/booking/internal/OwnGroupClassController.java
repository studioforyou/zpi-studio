package studioforyou.pl.backend.booking.internal;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.GroupClassDtos.BookRequest;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.CancelRequest;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.GroupClassResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.HistoryEntryResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.JoinWaitlistRequest;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ReservationResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.ParticipantResponse;
import studioforyou.pl.backend.booking.internal.GroupClassDtos.WaitlistEntryResponse;
import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.ListResponse;

/** Self-service for the authenticated account's own participant and its children. */
@RestController
@RequestMapping("/api/v1/me/profiles/{profileId}")
@Tag(name = "booking")
class OwnGroupClassController {

	private final GroupClassBookingService bookings;

	private final GroupClassWaitlistService waitlists;

	OwnGroupClassController(GroupClassBookingService bookings, GroupClassWaitlistService waitlists) {
		this.bookings = bookings;
		this.waitlists = waitlists;
	}

	@GetMapping("/group-classes")
	@Operation(summary = "Browse group classes", operationId = "listOwnGroupClassOffer",
			description = "Public group classes overlapping [from, to), with current availability and this profile's booking options. Maximum window: 93 days.")
	ListResponse<GroupClassResponse> list(@PathVariable UUID profileId, @RequestParam Instant from,
			@RequestParam Instant to, Authentication authentication) {
		return new ListResponse<>(this.bookings.list(actor(authentication), profileId, from, to, false));
	}

	@GetMapping("/group-classes/{eventId}")
	@Operation(summary = "Get a group class", operationId = "getOwnGroupClassOffer")
	GroupClassResponse get(@PathVariable UUID profileId, @PathVariable UUID eventId,
			Authentication authentication) {
		return this.bookings.get(actor(authentication), profileId, eventId);
	}

	@GetMapping("/reservations")
	@Operation(summary = "List my upcoming group class reservations", operationId = "listOwnGroupClassReservations",
			description = "The profile's active future group class reservations overlapping [from, to), using the same class projection as the offer.")
	ListResponse<GroupClassResponse> reservations(@PathVariable UUID profileId, @RequestParam Instant from,
			@RequestParam Instant to, Authentication authentication) {
		return new ListResponse<>(this.bookings.list(actor(authentication), profileId, from, to, true));
	}

	@GetMapping("/training-history")
	@Operation(summary = "List my training history", operationId = "listOwnTrainingHistory",
			description = "The profile's booked events that started in [from, to) before now, latest first, each with its attendance or cancellation outcome. Maximum window: 93 days.")
	ListResponse<HistoryEntryResponse> history(@PathVariable UUID profileId, @RequestParam Instant from,
			@RequestParam Instant to, Authentication authentication) {
		return new ListResponse<>(this.bookings.history(actor(authentication), profileId, from, to));
	}

	@GetMapping("/group-classes/{eventId}/participants")
	@Operation(summary = "List co-participants in my booked group class", operationId = "listOwnGroupClassParticipants",
			description = "Requires an active reservation for the selected authorized participant. Returns only display names and the self marker; no other participant identifiers or contact details.")
	@ApiResponse(responseCode = "403", description = "The selected participant has no active reservation for this class.")
	ListResponse<ParticipantResponse> participants(@PathVariable UUID profileId, @PathVariable UUID eventId,
			Authentication authentication) {
		return new ListResponse<>(this.bookings.participants(actor(authentication), profileId, eventId));
	}

	@GetMapping("/reservations/{reservationId}")
	@Operation(summary = "Get my group class reservation", operationId = "getOwnGroupClassReservation")
	ReservationResponse reservation(@PathVariable UUID profileId, @PathVariable UUID reservationId,
			Authentication authentication) {
		return this.bookings.getReservation(actor(authentication), profileId, reservationId);
	}

	@PostMapping("/group-classes/{eventId}/reservations")
	@ApiResponse(responseCode = "201", description = "A place and pass entry were reserved; Location identifies the reservation.")
	@ApiResponse(responseCode = "200", description = "The existing active reservation, without another pass charge.")
	@ApiResponse(responseCode = "409", description = "CLASS_FULL.")
	@ApiResponse(responseCode = "422", description = "Booking window, participation or pass requirements are not met.")
	@Operation(summary = "Book a group class", operationId = "bookOwnGroupClass",
			description = "Revalidates eligibility and atomically reserves a place and pass entry. A retry when the profile already has an active reservation returns it without charging another entry.")
	ResponseEntity<ReservationResponse> book(@PathVariable UUID profileId, @PathVariable UUID eventId,
			@Valid @RequestBody BookRequest request, Authentication authentication) {
		GroupClassBookingService.BookingResult result = this.bookings.book(actor(authentication), profileId,
				eventId, request.customerPassId());
		if (!result.created()) {
			return ResponseEntity.ok(result.reservation());
		}
		return ResponseEntity.created(URI.create("/api/v1/me/profiles/" + profileId + "/reservations/"
				+ result.reservation().id())).body(result.reservation());
	}

	@PostMapping("/reservations/{reservationId}/cancel")
	@ApiResponse(responseCode = "409", description = "VERSION_CONFLICT or CANCELLATION_CONSEQUENCE_CHANGED; refresh and confirm again. "
			+ "VERSION_CONFLICT also when a waiting participant's pass changed while the freed place was given to them.")
	@ApiResponse(responseCode = "422", description = "The stored cancellation terms or occurrence state do not allow this cancellation.")
	@Operation(summary = "Cancel my group class reservation", operationId = "cancelOwnGroupClassReservation",
			description = "Uses the cancellation terms stored at booking. A stale version or a changed entry-return consequence rejects the action without cancelling or changing the pass balance. "
					+ "In the same transaction the freed place goes to the first participants on the class's waiting list who meet the booking rules.")
	ReservationResponse cancel(@PathVariable UUID profileId, @PathVariable UUID reservationId,
			@Valid @RequestBody CancelRequest request, Authentication authentication) {
		return this.bookings.cancel(actor(authentication), profileId, reservationId, request);
	}

	@PostMapping("/group-classes/{eventId}/waitlist-entries")
	@ApiResponse(responseCode = "201", description = "The profile joined the waiting list; Location identifies its entry. Nothing is charged yet.")
	@ApiResponse(responseCode = "200", description = "The profile's existing place in the queue, unchanged.")
	@ApiResponse(responseCode = "409", description = "ALREADY_RESERVED, or CLASS_NOT_FULL: a place can be booked instead.")
	@ApiResponse(responseCode = "422", description = "Booking window, waiting-list setting, participation or pass requirements are not met.")
	@Operation(summary = "Join the waiting list of a full group class", operationId = "joinOwnGroupClassWaitlist",
			description = "Requires what booking requires, except a free place, plus an enabled waiting list. When a place frees up, "
					+ "it is given automatically, in queue order, to the first waiting participants who can book it then: the chosen pass "
					+ "is charged or, when it can no longer be used, another eligible pass of the participant.")
	ResponseEntity<WaitlistEntryResponse> joinWaitlist(@PathVariable UUID profileId, @PathVariable UUID eventId,
			@Valid @RequestBody JoinWaitlistRequest request, Authentication authentication) {
		GroupClassWaitlistService.JoinResult result = this.waitlists.join(actor(authentication), profileId, eventId,
				request.customerPassId());
		if (!result.created()) {
			return ResponseEntity.ok(result.entry());
		}
		return ResponseEntity.created(URI.create("/api/v1/me/profiles/" + profileId + "/waitlist-entries/"
				+ result.entry().id())).body(result.entry());
	}

	@GetMapping("/waitlist-entries/{entryId}")
	@Operation(summary = "Get my waiting list entry", operationId = "getOwnWaitlistEntry")
	WaitlistEntryResponse waitlistEntry(@PathVariable UUID profileId, @PathVariable UUID entryId,
			Authentication authentication) {
		return this.waitlists.get(actor(authentication), profileId, entryId);
	}

	@PostMapping("/waitlist-entries/{entryId}/confirm")
	@ApiResponse(responseCode = "200", description = "The offered place is booked; the entry is PROMOTED with its reservation.")
	@ApiResponse(responseCode = "409", description = "WAITLIST_OFFER_EXPIRED: the time to confirm has run out.")
	@ApiResponse(responseCode = "422", description = "WAITLIST_ENTRY_NOT_OFFERED, or the booking rules are not met now.")
	@Operation(summary = "Confirm a place offered from the waiting list", operationId = "confirmOwnGroupClassWaitlistOffer",
			description = "With a confirmation time in the class's booking policy, a freed place is held for the first eligible waiting "
					+ "participant until offerExpiresAt. Confirming books it like a booking, charging the chosen pass or, when it can no "
					+ "longer be used, another eligible one. Repeating it returns the entry with its reservation.")
	WaitlistEntryResponse confirmWaitlistOffer(@PathVariable UUID profileId, @PathVariable UUID entryId,
			Authentication authentication) {
		return this.waitlists.confirm(actor(authentication), profileId, entryId);
	}

	@PostMapping("/waitlist-entries/{entryId}/cancel")
	@ApiResponse(responseCode = "422", description = "WAITLIST_ENTRY_NOT_WAITING: the participant already got a place from the list.")
	@Operation(summary = "Leave the waiting list", operationId = "leaveOwnGroupClassWaitlist",
			description = "Gives up the profile's place in the queue, or declines an offered place, which then goes to the next participant. "
					+ "Repeating it returns the cancelled entry unchanged.")
	WaitlistEntryResponse leaveWaitlist(@PathVariable UUID profileId, @PathVariable UUID entryId,
			Authentication authentication) {
		return this.waitlists.leave(actor(authentication), profileId, entryId);
	}

	private static UUID actor(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.activeAccountId()
				.orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED, "An active account is required."));
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

}
