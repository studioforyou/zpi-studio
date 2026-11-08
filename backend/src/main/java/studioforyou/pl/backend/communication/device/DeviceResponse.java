package studioforyou.pl.backend.communication.device;

import java.time.Instant;

/**
 * A registered device. The token itself is not returned: the app already has it.
 *
 * @param registeredAt when the token was first registered
 * @param lastSeenAt when the app last registered it
 */
public record DeviceResponse(DevicePlatform platform, Instant registeredAt, Instant lastSeenAt) {

	static DeviceResponse from(DeviceTokens.Device device) {
		return new DeviceResponse(device.platform(), device.registeredAt(), device.lastSeenAt());
	}

}
