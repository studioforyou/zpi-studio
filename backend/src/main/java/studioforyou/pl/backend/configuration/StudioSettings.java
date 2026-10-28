package studioforyou.pl.backend.configuration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * Studio-wide settings for business modules: the studio's time zone, today's date in it,
 * and the studio's currency. While no studio row exists, the {@code studio} column defaults apply.
 */
@Component
public class StudioSettings {

	static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Warsaw");

	static final String DEFAULT_CURRENCY = "PLN";

	private final StudioRepository studios;

	private final Clock clock = Clock.systemUTC();

	public StudioSettings(StudioRepository studios) {
		this.studios = studios;
	}

	public ZoneId zone() {
		return studios.findFirstBy().map((studio) -> ZoneId.of(studio.getTimeZone())).orElse(DEFAULT_ZONE);
	}

	public LocalDate today() {
		return LocalDate.now(clock.withZone(zone()));
	}

	/** The ISO 4217 code of the currency prices are kept in. */
	public String currency() {
		return studios.findFirstBy().map(Studio::getCurrency).orElse(DEFAULT_CURRENCY);
	}

}
