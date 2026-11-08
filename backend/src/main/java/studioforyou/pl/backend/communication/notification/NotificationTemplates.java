package studioforyou.pl.backend.communication.notification;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {{name}}} placeholders in notification templates. Names are Polish words, so the
 * administrator reads them without a manual, and may have spaces, e.g. {@code {{ile przed}}}.
 */
public final class NotificationTemplates {

	private static final Pattern PLACEHOLDER = Pattern
		.compile("\\{\\{\\s*(\\p{L}[\\p{L}\\p{N}]*(?:\\s+[\\p{L}\\p{N}]+)*)\\s*}}");

	private static final Pattern SPACES = Pattern.compile("\\s+");

	private NotificationTemplates() {
	}

	/** The template with every known placeholder replaced; an unknown one is left out. */
	public static String render(String template, Map<String, String> values) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			String value = values.getOrDefault(name(matcher), "");
			matcher.appendReplacement(result, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/** Names of the placeholders used in the template, in order of first use. */
	public static Set<String> placeholders(String template) {
		Set<String> names = new LinkedHashSet<>();
		Matcher matcher = PLACEHOLDER.matcher(template);
		while (matcher.find()) {
			names.add(name(matcher));
		}
		return names;
	}

	/** The placeholder name with inner whitespace as single spaces: {@code {{ile  przed}}} is "ile przed". */
	private static String name(Matcher matcher) {
		return SPACES.matcher(matcher.group(1)).replaceAll(" ");
	}

}
