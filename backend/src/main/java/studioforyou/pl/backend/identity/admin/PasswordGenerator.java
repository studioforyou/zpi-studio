package studioforyou.pl.backend.identity.admin;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Random passwords for accounts created by an administrator: 16 characters with at least one upper-case
 * letter, lower-case letter, digit and symbol. Characters easy to confuse when retyping (I, l, 1, O, 0)
 * are left out.
 */
final class PasswordGenerator {

	static final int LENGTH = 16;

	private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";

	private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";

	private static final String DIGITS = "23456789";

	private static final String SYMBOLS = "!@#$%&*?-_+=";

	private static final String ALL = UPPER + LOWER + DIGITS + SYMBOLS;

	private static final SecureRandom RANDOM = new SecureRandom();

	private PasswordGenerator() {
	}

	static String generate() {
		List<Character> characters = new ArrayList<>(LENGTH);
		for (String required : List.of(UPPER, LOWER, DIGITS, SYMBOLS)) {
			characters.add(randomFrom(required));
		}
		while (characters.size() < LENGTH) {
			characters.add(randomFrom(ALL));
		}
		Collections.shuffle(characters, RANDOM);
		StringBuilder password = new StringBuilder(LENGTH);
		characters.forEach(password::append);
		return password.toString();
	}

	private static char randomFrom(String alphabet) {
		return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
	}

}
