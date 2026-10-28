package studioforyou.pl.backend.identity.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class PasswordGeneratorTest {

	@RepeatedTest(50)
	void containsEveryKindOfCharacterAndNoneThatAreEasyToConfuse() {
		String password = PasswordGenerator.generate();

		assertThat(password).hasSize(16)
			.containsPattern("[A-Z]")
			.containsPattern("[a-z]")
			.containsPattern("[0-9]")
			.containsPattern("[^A-Za-z0-9]")
			.doesNotContainPattern("[Il1O0]");
	}

	@Test
	void differsEveryTime() {
		Set<String> passwords = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			passwords.add(PasswordGenerator.generate());
		}
		assertThat(passwords).hasSize(1000);
	}

}
