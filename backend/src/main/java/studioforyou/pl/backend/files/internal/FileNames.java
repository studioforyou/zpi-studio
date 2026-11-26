package studioforyou.pl.backend.files.internal;

import java.util.Arrays;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import studioforyou.pl.backend.files.FileType;

/** The names stored files are shown and downloaded with. */
final class FileNames {

	/** Longest name, in characters, with the extension. */
	static final int MAX_LENGTH = 150;

	/** The name of a file the client gave no usable name. */
	static final String FALLBACK = "file";

	/** Characters some systems do not allow in file names. */
	private static final String FORBIDDEN = "\"*/:<>?\\|";

	private FileNames() {
	}

	/**
	 * The client's name without a path, control and forbidden characters, ending with the
	 * extension of the file's real type: a wrong known extension is replaced, any other is kept
	 * and the right one added.
	 */
	static String of(@Nullable String original, FileType type) {
		String name = (original != null) ? original : "";
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		name = name.substring(slash + 1);
		StringBuilder clean = new StringBuilder();
		name.codePoints()
			.filter((c) -> !Character.isISOControl(c) && FORBIDDEN.indexOf(c) < 0)
			.forEach(clean::appendCodePoint);
		name = strip(clean.toString());
		if (!type.matchesName(name)) {
			int dot = name.lastIndexOf('.');
			if (dot > 0 && isKnownExtension(name.substring(dot + 1))) {
				name = name.substring(0, dot);
			}
			name = strip(name);
			name = (name.isEmpty() ? FALLBACK : name) + "." + type.extension();
		}
		if (name.length() > MAX_LENGTH) {
			int dot = name.lastIndexOf('.');
			String extension = name.substring(dot);
			int end = MAX_LENGTH - extension.length();
			if (Character.isHighSurrogate(name.charAt(end - 1))) {
				end--;
			}
			name = strip(name.substring(0, end)) + extension;
		}
		return name;
	}

	/** Without spaces and dots around it, so it is neither hidden nor blank. */
	private static String strip(String name) {
		int start = 0;
		int end = name.length();
		while (start < end && (Character.isWhitespace(name.charAt(start)) || name.charAt(start) == '.')) {
			start++;
		}
		while (end > start && (Character.isWhitespace(name.charAt(end - 1)) || name.charAt(end - 1) == '.')) {
			end--;
		}
		return name.substring(start, end);
	}

	private static boolean isKnownExtension(String extension) {
		String lower = extension.toLowerCase(Locale.ROOT);
		return Arrays.stream(FileType.values()).anyMatch((type) -> type.matchesName("x." + lower));
	}

}
