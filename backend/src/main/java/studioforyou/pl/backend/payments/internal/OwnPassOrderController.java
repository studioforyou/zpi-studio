package studioforyou.pl.backend.payments.internal;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.identity.CurrentAccount;
import studioforyou.pl.backend.shared.api.ApiProblem;

/**
 * Clients buy passes for their own profile and for the children they are guardians of. The
 * payment is simulated until a payment provider is connected: paying an order confirms it at once.
 */
@RestController
@Tag(name = "payments")
public class OwnPassOrderController {

	private final PassOrderService orders;

	OwnPassOrderController(PassOrderService orders) {
		this.orders = orders;
	}

	@PostMapping("/api/v1/me/profiles/{profileId}/pass-orders")
	@Operation(summary = "Order a pass", operationId = "placeOwnPassOrder",
			description = "Orders one pass of the variant for the account's own profile or a child it is a guardian "
					+ "of. The client must accept the terms of sale. The pass starts on startsOn, or right after "
					+ "another pass of the profile (startAfterPassId): the day after its last day, or today if it has "
					+ "already ended. The pass waits for the payment (PENDING_PAYMENT) until the order is paid.")
	@ApiResponse(responseCode = "201", description = "The order, waiting for the payment.")
	@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, also when the terms are not accepted, neither "
			+ "or both of startsOn and startAfterPassId are given, startsOn is in the past or startAfterPassId is not "
			+ "a pass of the profile.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "No such profile, the account does not act for it, or no such "
			+ "variant.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "CUSTOMER_NOT_ACTIVE, PASS_PRODUCT_ARCHIVED, PASS_VARIANT_INACTIVE, "
			+ "PASS_AUDIENCE_MISMATCH, or PASS_NOT_USABLE when the followed pass is cancelled or waits for its "
			+ "payment.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public ResponseEntity<PassOrderResponse> place(@PathVariable UUID profileId,
			@Valid @RequestBody PlacePassOrderRequest request, Authentication authentication) {
		PassOrderService.PassOrder order = orders.place(CurrentAccount.of(authentication).id(), profileId,
				request.toCommand());
		return ResponseEntity.status(HttpStatus.CREATED).body(PassOrderResponse.from(order));
	}

	@PostMapping("/api/v1/me/pass-orders/{orderId}/mock-payment")
	@Operation(summary = "Pay for a pass order (simulated)", operationId = "payOwnPassOrderWithMock",
			description = "Simulates the online payment until a payment provider is connected: no money is taken, the "
					+ "payment is confirmed at once, the order becomes PAID and its pass is activated (SCHEDULED when "
					+ "it starts after today, ACTIVE otherwise).")
	@ApiResponse(responseCode = "200", description = "The paid order with its activated pass.")
	@ApiResponse(responseCode = "404", description = "No such order placed by the account.", content = @Content(
			mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "ORDER_NOT_PAYABLE: the order has already been paid or was "
			+ "cancelled.", content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ApiProblem.class)))
	public PassOrderResponse payWithMock(@PathVariable UUID orderId, Authentication authentication) {
		return PassOrderResponse.from(orders.payWithMock(CurrentAccount.of(authentication).id(), orderId));
	}

}
