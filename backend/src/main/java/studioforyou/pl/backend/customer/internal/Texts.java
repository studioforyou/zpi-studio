package studioforyou.pl.backend.customer.internal;

import java.util.regex.Pattern;

/** Trimming of user-entered text. */
final class Texts {

	// Unicode White_Space plus BOM, matching the mobile client's String.trim().
	private static final Pattern EDGE_WHITESPACE = Pattern.compile(
			"^[\\s\\p{Z}\\u0085\\uFEFF]+|[\\s\\p{Z}\\u0085\\uFEFF]+$");

	private Texts() {
	}

	static String trim(String value) {
		return (value == null) ? null : EDGE_WHITESPACE.matcher(value).replaceAll("");
	}

	static String trimToNull(String value) {
		String trimmed = trim(value);
		return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
	}

}
