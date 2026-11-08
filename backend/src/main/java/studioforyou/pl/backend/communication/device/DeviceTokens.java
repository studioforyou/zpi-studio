package studioforyou.pl.backend.communication.device;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * FCM registration tokens of the accounts' devices ({@code device_token}). One account can have
 * several devices; a token belongs to one account at a time.
 */
@Repository
public class DeviceTokens {

	private final JdbcClient jdbc;

	DeviceTokens(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Registers the token for the account, or refreshes it. A token already registered for another
	 * account (another person signed in on the same phone) moves to this account, and a revoked one
	 * becomes active again.
	 */
	public Device register(UUID accountId, String token, DevicePlatform platform) {
		return jdbc.sql("""
				INSERT INTO device_token (account_id, token, platform)
				VALUES (:accountId, :token, :platform)
				ON CONFLICT (token) DO UPDATE
				SET account_id = EXCLUDED.account_id, platform = EXCLUDED.platform, last_seen_at = now(),
				    revoked_at = NULL
				RETURNING platform, created_at, last_seen_at
				""")
			.param("accountId", accountId)
			.param("token", token)
			.param("platform", platform.name())
			.query((rs, rowNum) -> new Device(DevicePlatform.valueOf(rs.getString("platform")),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getObject("last_seen_at", OffsetDateTime.class).toInstant()))
			.single();
	}

	/** Stops sending to the account's token, e.g. on sign-out. */
	public void revoke(UUID accountId, String token) {
		jdbc.sql("""
				UPDATE device_token SET revoked_at = now()
				WHERE account_id = :accountId AND token = :token AND revoked_at IS NULL
				""")
			.param("accountId", accountId)
			.param("token", token)
			.update();
	}

	/** Stops sending to a token FCM reported as no longer registered, whoever it belongs to. */
	public void revokeInvalid(String token) {
		jdbc.sql("UPDATE device_token SET revoked_at = now() WHERE token = :token AND revoked_at IS NULL")
			.param("token", token)
			.update();
	}

	/** Active tokens of the account. */
	public List<String> activeTokens(UUID accountId) {
		return jdbc.sql("SELECT token FROM device_token WHERE account_id = :accountId AND revoked_at IS NULL")
			.param("accountId", accountId)
			.query(String.class)
			.list();
	}

	/** Accounts among the given ones that have at least one active token. */
	public List<UUID> accountsWithDevices(Collection<UUID> accountIds) {
		if (accountIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT account_id FROM device_token
				WHERE account_id IN (:accountIds) AND revoked_at IS NULL
				""")
			.param("accountIds", accountIds)
			.query(UUID.class)
			.list();
	}

	/**
	 * @param registeredAt when the token was first registered
	 * @param lastSeenAt when the app last registered it
	 */
	public record Device(DevicePlatform platform, Instant registeredAt, Instant lastSeenAt) {
	}

}
