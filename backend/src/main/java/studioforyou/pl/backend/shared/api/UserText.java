package studioforyou.pl.backend.shared.api;

import java.util.regex.Pattern;

/** Normalization of text users type into forms. */
public final class UserText {

	// Unicode White_Space plus BOM, matching the mobile client's String.trim().
	private static final Pattern EDGE_WHITESPACE = Pattern.compile(
			"^[\\s\\p{Z}\\u0085\\uFEFF]+|[\\s\\p{Z}\\u0085\\uFEFF]+$");

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
