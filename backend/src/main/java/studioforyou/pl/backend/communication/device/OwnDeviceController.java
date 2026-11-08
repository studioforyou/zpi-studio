package studioforyou.pl.backend.communication.device;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studioforyou.pl.backend.shared.api.AccountAuthentication;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;

/** The signed-in account's devices that receive push notifications. */
@RestController
@RequestMapping("/api/v1/me/devices")
@Tag(name = "communication")
class OwnDeviceController {

	private final DeviceTokens devices;

	OwnDeviceController(DeviceTokens devices) {
		this.devices = devices;
	}

	@PostMapping
	@Operation(summary = "Register my device for push notifications", operationId = "registerOwnDevice",
			description = "Called by the mobile app after sign-in and whenever Firebase gives it a new token. "
					+ "Idempotent: registering a known token refreshes it. A token registered for another "
					+ "account (another person signed in on this phone) moves to this account.")
	@ApiResponse(responseCode = "200", description = "The device is registered.")
	public DeviceResponse register(@Valid @RequestBody RegisterDeviceRequest request, Authentication authentication) {
		return DeviceResponse.from(devices.register(actor(authentication), request.token().strip(), request.platform()));
	}

	@DeleteMapping("/{token}")
	@Operation(summary = "Unregister my device", operationId = "unregisterOwnDevice",
			description = "Called by the mobile app before sign-out, so the phone stops getting this account's "
					+ "notifications. Unknown tokens and tokens of other accounts are ignored.")
	@ApiResponse(responseCode = "204", description = "The device no longer gets push notifications.")
	public ResponseEntity<Void> unregister(@PathVariable String token, Authentication authentication) {
		devices.revoke(actor(authentication), token);
		return ResponseEntity.noContent().build();
	}

	private static UUID actor(Authentication authentication) {
		if (authentication instanceof AccountAuthentication account) {
			return account.activeAccountId()
				.orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED, "An active account is required."));
		}
		throw new ApiException(ErrorCode.UNAUTHENTICATED, "Missing, expired or invalid access token.");
	}

}
