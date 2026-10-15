package studioforyou.pl.backend.configuration;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Studio settings needed by the clients.")
public record StudioResponse(
		@Schema(example = "studio4U") String name,
		@Schema(description = "IANA time zone of the studio; local studio times use it.", example = "Europe/Warsaw") String timeZone,
		@Schema(description = "ISO 4217 currency code.", example = "PLN") String currency) {

	static StudioResponse from(Studio studio) {
		return new StudioResponse(studio.getName(), studio.getTimeZone(), studio.getCurrency());
	}

}
