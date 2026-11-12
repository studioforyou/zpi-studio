package studioforyou.pl.backend.booking.internal;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.booking.internal.GroupClassDtos.ReservationResponse;
import studioforyou.pl.backend.shared.api.AccountAuthentication;

/** Make-up redemption joins the same places, booking rules and cancellation flow as a normal booking. */
@RestController
@RequestMapping("/api/v1/me/profiles/{profileId}/makeup-credits/{creditId}/group-classes")
@Tag(name = "booking")
class OwnMakeupBookingController {

	private final GroupClassBookingService bookings;

	OwnMakeupBookingController(GroupClassBookingService bookings) {
		this.bookings = bookings;
	}

	@GetMapping
	@Operation(summary = "Find group classes for my make-up credit", operationId = "listOwnMakeupClasses",
			description = "Authorizes the participant and credit, including credits from expired passes. "
					+ "Uses historical product coverage, the make-up expiry rule and normal participation requirements. "
					+ "Only compatible future classes appear; unavailable places or missing documents have reason codes. Maximum window: 93 days.")
	GroupClassBookingService.MakeupOffer list(@PathVariable UUID profileId, @PathVariable UUID creditId,
			@RequestParam Instant from, @RequestParam Instant to, AccountAuthentication authentication) {
		return bookings.listMakeup(authentication.requireActiveAccountId(), profileId, creditId, from, to);
	}

	@PostMapping("/{eventId}/reservations")
	@Operation(summary = "Book a group class with my make-up credit", operationId = "bookOwnMakeupClass",
			description = "Reserves a place and marks the credit used atomically, without debiting the source pass. "
					+ "An identical retry returns 200; a new reservation returns 201. Concurrent reuse and unavailable places are rejected.")
	ResponseEntity<ReservationResponse> book(@PathVariable UUID profileId, @PathVariable UUID creditId,
			@PathVariable UUID eventId, AccountAuthentication authentication) {
		var result = bookings.bookMakeup(authentication.requireActiveAccountId(), profileId, eventId, creditId);
		return result.created() ? ResponseEntity.created(URI.create("/api/v1/me/profiles/" + profileId
				+ "/reservations/" + result.reservation().id())).body(result.reservation()) : ResponseEntity.ok(result.reservation());
	}
}
