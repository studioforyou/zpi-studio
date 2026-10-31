package studioforyou.pl.backend.shared.api;

import java.util.regex.Pattern;

/** Normalization of text users type into forms. */
public final class UserText {

	// Unicode White_Space plus BOM, matching the mobile client's String.trim().
	private static final Pattern EDGE_WHITESPACE = Pattern.compile(
			"^[\\s\\p{Z}\\u0085\\uFEFF]+|[\\s\\p{Z}\\u0085\\uFEFF]+$");

	/**
	 * A person's name: words of letters joined by spaces, hyphens, apostrophes or dots,
	 * e.g. {@code Anna-Maria O'Neil}; no digits or other symbols. Empty text matches, so
	 * required names also need {@code @NotBlank}.
	 */
	public static final String PERSON_NAME = "(?:[\\p{L}\\p{M}]+(?:[ '’.-]+[\\p{L}\\p{M}]+)*\\.?)?";

	/** A phone number: an optional leading plus, then digits separated by spaces, dashes or parentheses. */
	public static final String PHONE = "\\+?[0-9][0-9 ()-]*[0-9]";

	/** {@link #PERSON_NAME} and {@link #PHONE} in words, for API documentation. */
	public static final String CHARACTER_RULES = "Names may contain only letters, spaces, hyphens, apostrophes and dots; "
			+ "phone numbers only digits, spaces, dashes, parentheses and a leading plus.";

	private UserText() {
	}

	/** Removes surrounding Unicode whitespace; {@code null} stays {@code null}. */
	public static String trim(String value) {
		return (value == null) ? null : EDGE_WHITESPACE.matcher(value).replaceAll("");
	}

	/** Like {@link #trim(String)}, but text that is empty after trimming becomes {@code null}. */
	public static String trimToNull(String value) {
		String trimmed = trim(value);
		return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
	}

}
