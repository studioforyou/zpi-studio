package studioforyou.pl.backend.integrations.calendar;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The private calendar feed of each account ({@code calendar_feed}): one row per account, holding
 * the hash of its current token. Revoking keeps the row with {@code revoked_at} set; issuing a new
 * token replaces the hash and clears it.
 */
@Repository
class CalendarFeedStore {

	private final JdbcClient jdbc;

	CalendarFeedStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Saves the hash of the account's new token; the previous token stops working. */
	Instant replace(UUID accountId, String tokenHash) {
		return this.jdbc.sql("""
				INSERT INTO calendar_feed (account_id, token_hash, created_at, revoked_at)
				VALUES (:accountId, :tokenHash, clock_timestamp(), NULL)
				ON CONFLICT (account_id) DO UPDATE
				SET token_hash = EXCLUDED.token_hash, created_at = EXCLUDED.created_at, revoked_at = NULL
				RETURNING created_at
				""")
			.param("accountId", accountId)
			.param("tokenHash", tokenHash)
			.query(OffsetDateTime.class)
			.single()
			.toInstant();
	}

	/** When the account's current token was issued, if it has one that was not revoked. */
	Optional<Instant> findActiveCreatedAt(UUID accountId) {
		return this.jdbc.sql("SELECT created_at FROM calendar_feed WHERE account_id = :accountId AND revoked_at IS NULL")
			.param("accountId", accountId)
			.query(OffsetDateTime.class)
			.optional()
			.map(OffsetDateTime::toInstant);
	}

	/** Revokes the account's current token. Does nothing without one. */
	void revoke(UUID accountId) {
		this.jdbc.sql("""
				UPDATE calendar_feed SET revoked_at = clock_timestamp()
				WHERE account_id = :accountId AND revoked_at IS NULL
				""")
			.param("accountId", accountId)
			.update();
	}

	/** The account whose current token has the hash, if the token was not revoked. */
	Optional<UUID> findAccountByActiveTokenHash(String tokenHash) {
		return this.jdbc.sql("SELECT account_id FROM calendar_feed WHERE token_hash = :tokenHash AND revoked_at IS NULL")
			.param("tokenHash", tokenHash)
			.query(UUID.class)
			.optional();
	}

}
