package studioforyou.pl.backend.shared.api;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * An amount of money in the API: integer minor units plus an ISO 4217 currency code.
 * The database keeps money as {@code NUMERIC(10,2)}.
 */
@Schema(description = "Money in minor units, e.g. 12000 PLN = 120.00 PLN.")
public record Money(
		@Schema(example = "12000") @NotNull @Min(0) @Max(MAX_AMOUNT_MINOR) Long amountMinor,
		@Schema(example = "PLN") @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {

	/** The largest amount a {@code NUMERIC(10,2)} column can hold, in minor units. */
	public static final long MAX_AMOUNT_MINOR = 9_999_999_999L;

	public static Money of(BigDecimal amount, String currency) {
		return new Money(amount.movePointRight(2).longValueExact(), currency);
	}

	public BigDecimal toAmount() {
		return BigDecimal.valueOf(amountMinor, 2);
	}

}
