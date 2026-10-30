package studioforyou.pl.backend.membership.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** A nonzero entry correction; the original pass terms and previous ledger records stay intact. */
@Schema(name = "CorrectPassEntriesRequest")
public record CorrectPassEntriesRequest(
		@Schema(example = "2", description = "Signed, nonzero change in remaining entries.")
		@JsonDeserialize(using = CorrectPassEntriesRequest.StrictInteger.class)
		@NotNull @Min(-32767) @Max(32767) Integer entriesDelta,
		@Schema(example = "Correction of a mistaken charge") @Size(max = 500) String reason,
		@Schema(description = "Current pass version; a stale value returns 409 VERSION_CONFLICT.")
		@JsonDeserialize(using = CorrectPassEntriesRequest.StrictInteger.class)
		@NotNull @Min(0) Integer version) {

	CustomerPassService.EntryCorrection toCommand() {
		return new CustomerPassService.EntryCorrection(entriesDelta, reason, version);
	}

	/** This accounting request must not truncate fractions or coerce strings to integers. */
	public static class StrictInteger extends ValueDeserializer<Integer> {

		@Override
		public Integer deserialize(JsonParser parser, DeserializationContext context) {
			if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
				return context.reportInputMismatch(Integer.class, "Expected a JSON integer.");
			}
			return parser.getIntValue();
		}

	}

}
