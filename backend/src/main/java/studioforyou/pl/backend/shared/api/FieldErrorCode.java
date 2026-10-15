package studioforyou.pl.backend.shared.api;

/**
 * Codes used in {@code errors[].code} of a {@code VALIDATION_FAILED} response.
 */
public enum FieldErrorCode {

	REQUIRED,
	INVALID_FORMAT,
	INVALID_VALUE,
	TOO_SHORT,
	TOO_LONG,
	OUT_OF_RANGE

}
