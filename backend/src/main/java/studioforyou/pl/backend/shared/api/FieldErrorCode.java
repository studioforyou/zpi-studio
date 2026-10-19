package studioforyou.pl.backend.shared.api;

/**
 * Codes describing why a single field failed validation.
 */
public enum FieldErrorCode {

	REQUIRED,
	INVALID_FORMAT,
	INVALID_VALUE,
	TOO_SHORT,
	TOO_LONG,
	OUT_OF_RANGE

}
