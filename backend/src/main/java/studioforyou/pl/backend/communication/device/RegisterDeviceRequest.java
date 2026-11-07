package studioforyou.pl.backend.communication.device;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param token the FCM registration token the app got from Firebase
 * @param platform the device's platform
 */
public record RegisterDeviceRequest(
		@Schema(description = "FCM registration token from FirebaseMessaging.getToken().") @NotBlank @Size(max = 512) String token,
		@NotNull DevicePlatform platform) {
}
