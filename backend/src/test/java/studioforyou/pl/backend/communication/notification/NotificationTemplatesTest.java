package studioforyou.pl.backend.communication.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class NotificationTemplatesTest {

	@Test
	void fillsPlaceholdersAndLeavesOutUnknownOnes() {
		assertThat(NotificationTemplates.render("{{zajęcia}}: {{ dzień }}, godz. {{godzina}}{{missing}}.",
				Map.of("zajęcia", "Pilates", "dzień", "jutro", "godzina", "18:00")))
			.isEqualTo("Pilates: jutro, godz. 18:00.");
	}

	@Test
	void keepsSpecialCharactersOfValues() {
		assertThat(NotificationTemplates.render("Sala: {{sala}}", Map.of("sala", "$1 \\ sala"))).isEqualTo("Sala: $1 \\ sala");
	}

	@Test
	void readsPolishNamesWithSpaces() {
		assertThat(NotificationTemplates.render("Za {{ile przed}}, {{ ile   przed }}!", Map.of("ile przed", "2 godz.")))
			.isEqualTo("Za 2 godz., 2 godz.!");
		assertThat(NotificationTemplates.placeholders("{{ile  przed}} {{dzień}}")).containsExactly("ile przed", "dzień");
	}

	@Test
	void listsUsedPlaceholdersInOrder() {
		assertThat(NotificationTemplates.placeholders("{{godzina}} {{zajęcia}} {{godzina}} {single} {{1x}}"))
			.containsExactly("godzina", "zajęcia");
	}

}
