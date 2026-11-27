package studioforyou.pl.backend.integrations.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Secret tokens of calendar feed links. Only the SHA-256 hash of a token is stored
 * ({@code calendar_feed.token_hash}).
 */
final class CalendarFeedTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private CalendarFeedTokens() {
	}

	/** A new random token: 32 bytes, URL-safe Base64 without padding (43 characters). */
	static String generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Lowercase hex SHA-256 of the token, as stored in {@code token_hash}. */
	static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

}
